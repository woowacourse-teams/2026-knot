#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = [
#     "httpx2[http2,brotli,zstd]",
#     "pydantic",
#     "pydantic-settings",
#     "typer",
# ]
# ///

# ruff: noqa: B008  # Typer uses Option calls as the CLI declaration syntax.

# ─── How to run ───
# 1. Export a Workspace-scoped OAuth access token and allowlist:
#      export NOTION_MCP_ACCESS_TOKEN="..."
#      export NOTION_MCP_WORKSPACE_ID="workspace-id"
#      export NOTION_MCP_ALLOWED_PAGE_IDS="page-id-1,page-id-2"
# 2. Run a retrieval-only live smoke benchmark:
#      uv run tools/llm-benchmark/run_mcp_live_benchmark.py --retrieval-only --case G-001
# 3. Never write the token to source, prompts, JSONL output, or reports.
# ──────────────────

"""Compare the active snapshot RAG boundary with a live read-only Notion MCP."""

from __future__ import annotations

import time
from contextlib import ExitStack, closing
from pathlib import Path
from typing import Final, TextIO

import typer
from benchmark_core import ContextPack
from benchmark_metadata import BenchmarkMetadata, create_benchmark_metadata
from gold_set import BenchmarkCase, GoldSetError, load_cases
from mcp_adapter import (
    LiveNotionMcpAdapter,
    McpAdapterError,
    McpContextBuildError,
    McpContextTiming,
    build_mcp_context,
)
from mcp_models import McpScope, McpSettings, McpToolCallValidationError, McpToolTrace
from mcp_tool_loop import (
    context_from_tool_executions,
    generate_with_mcp_tools,
)
from mcp_transport import (
    McpHttpClient,
    McpHttpError,
    McpProtocolError,
    McpTransportError,
)
from nim_client import (
    ChatMessage,
    NimClient,
    NimConfigurationError,
    NimRequestError,
    NimSettings,
    NimTransportError,
)
from pydantic import ValidationError
from retrieval_policy import plan_query
from rich.console import Console
from run_benchmark import (
    _SYSTEM_INSTRUCTIONS,
    BenchmarkRecord,
    _messages,
    _write_record,
)

_DEFAULT_GOLD_SET: Final[Path] = Path("backend/docs/llm-search-benchmark-gold-set.md")
_DEFAULT_OUTPUT: Final[Path] = Path(".benchmark-data/mcp-live-results.jsonl")
_NO_ANSWER_TEXT: Final[str] = (
    "현재 동기화된 팀 문서에서는 관련된 정보를 찾지 못했습니다. 최신 문서가 반영되지 않았다면 동기화 후 다시 검색해보세요."
)


def main(
    gold_set: Path = typer.Option(_DEFAULT_GOLD_SET, help="Markdown gold-set path."),
    output: Path = typer.Option(_DEFAULT_OUTPUT, help="JSONL output path."),
    workspace_id: str = typer.Option(
        "", envvar="NOTION_MCP_WORKSPACE_ID", help="Connected Notion Workspace ID."
    ),
    allowed_page_ids: str = typer.Option(
        "",
        envvar="NOTION_MCP_ALLOWED_PAGE_IDS",
        help="Comma-separated connected page IDs.",
    ),
    active_snapshot_id: str | None = typer.Option(
        None,
        envvar="NOTION_MCP_ACTIVE_SNAPSHOT_ID",
        help="Optional active snapshot identity.",
    ),
    case: str | None = typer.Option(None, help="Comma-separated case IDs."),
    repeats: int = typer.Option(1, min=1, help="Repeats per case."),
    top_k: int = typer.Option(3, min=1, max=3, help="Maximum related pages to fetch."),
    retrieval_only: bool = typer.Option(
        False, help="Measure MCP search/fetch without calling NIM."
    ),
    tool_calling: bool = typer.Option(
        True,
        "--tool-calling/--direct-retrieval",
        help="Let NIM choose the read-only MCP tools for normal runs.",
    ),
    run_id: str = typer.Option("", help="Stable identifier for this live run."),
    condition: str = typer.Option(
        "cold", help="Execution condition recorded as cold or warm."
    ),
) -> None:
    """Run scoped live Notion MCP observations without sending credentials to NIM."""
    console = Console()
    scope = _scope(workspace_id, allowed_page_ids, active_snapshot_id)
    try:
        mcp_settings = McpSettings()
        cases = _select_cases(load_cases(gold_set), case)
        nim_settings = None if retrieval_only else NimSettings()
        _validate_mcp_settings(mcp_settings)
        if nim_settings is not None:
            _validate_nim_settings(nim_settings)
        metadata = create_benchmark_metadata(
            run_id=run_id,
            phase="live",
            condition=condition,
            snapshot_id=active_snapshot_id or "notion-live",
            model="retrieval-only" if nim_settings is None else nim_settings.model,
            prompt=_SYSTEM_INSTRUCTIONS,
            generation_options={
                "tool_calling": tool_calling,
                "retrieval_only": retrieval_only,
                "top_k": top_k,
                "repeats": repeats,
                "max_pages": mcp_settings.max_pages,
                "search_tool": mcp_settings.search_tool,
                "fetch_tool": mcp_settings.fetch_tool,
                "active_snapshot_id": active_snapshot_id,
            },
        )
    except (
        McpTransportError,
        NimConfigurationError,
        ValidationError,
        ValueError,
    ) as error:
        console.print("[red]invalid live benchmark environment:[/red]", error)
        raise typer.Exit(code=2) from error
    output.parent.mkdir(parents=True, exist_ok=True)
    with ExitStack() as stack:
        transport = stack.enter_context(closing(McpHttpClient(mcp_settings)))
        adapter = LiveNotionMcpAdapter(
            transport,
            scope,
            search_tool=mcp_settings.search_tool,
            fetch_tool=mcp_settings.fetch_tool,
            max_pages=mcp_settings.max_pages,
        )
        chat_client = (
            None
            if nim_settings is None
            else stack.enter_context(closing(NimClient(nim_settings)))
        )
        with output.open("w", encoding="utf-8") as stream:
            for repeat in range(1, repeats + 1):
                for benchmark_case in cases:
                    _run_case(
                        stream,
                        adapter,
                        chat_client,
                        benchmark_case,
                        repeat,
                        top_k,
                        retrieval_only,
                        tool_calling,
                        scope,
                        metadata,
                    )
    console.print(f"[green]results written:[/green] {output}")


def _run_case(
    stream: TextIO,
    adapter: LiveNotionMcpAdapter,
    chat_client: NimClient | None,
    benchmark_case: BenchmarkCase,
    repeat: int,
    top_k: int,
    retrieval_only: bool,
    tool_calling: bool = False,
    scope: McpScope | None = None,
    metadata: BenchmarkMetadata | None = None,
) -> None:
    history: list[ChatMessage] = []
    tool_calling_mode = tool_calling and not retrieval_only
    for turn, question in enumerate(benchmark_case.turns, start=1):
        started = time.perf_counter()
        context_timing = McpContextTiming(ContextPack("", (), 0, 0), ())
        answer = ""
        error: str | None = None
        access_ms = 0.0
        model_ttft_ms: float | None = None
        model_total_ms: float | None = None
        try:
            query_plan = plan_query(question, _previous_questions(history))
            if query_plan.should_clarify:
                answer = query_plan.clarification_text
            else:
                if tool_calling and not retrieval_only:
                    if chat_client is None or scope is None:
                        raise NimTransportError(
                            "MCP tool-calling requires a chat client and scope"
                        )
                    outcome = generate_with_mcp_tools(
                        chat_client,
                        _messages(
                            question,
                            ContextPack("", (), 0, 0),
                            tuple(history),
                        ),
                        adapter,
                        scope,
                        search_limit=top_k,
                    )
                    context = context_from_tool_executions(outcome.executions)
                    context_timing = McpContextTiming(
                        context,
                        tuple(
                            execution.result.trace
                            for execution in outcome.executions
                        ),
                    )
                    access_ms = sum(
                        trace.elapsed_ms for trace in context_timing.traces
                    )
                    model_ttft_ms = outcome.model_ttft_ms
                    model_total_ms = outcome.model_total_ms
                    answer = (
                        _NO_ANSWER_TEXT
                        if context.retrieved_count == 0
                        else outcome.result.text
                    )
                else:
                    context_timing = build_mcp_context(
                        adapter, query_plan.search_query, top_k
                    )
                    if context_timing.context.retrieved_count == 0:
                        answer = _NO_ANSWER_TEXT
                    access_ms = (time.perf_counter() - started) * 1000
            if not answer and not retrieval_only and not tool_calling:
                if chat_client is None:
                    raise NimTransportError(
                        "NIM client is required unless --retrieval-only is enabled"
                    )
                result = chat_client.generate(
                    _messages(question, context_timing.context, tuple(history))
                )
                answer = result.text
                model_ttft_ms = result.ttft_ms
                model_total_ms = result.total_ms
            if access_ms == 0.0 and not tool_calling_mode:
                access_ms = (time.perf_counter() - started) * 1000
        except McpContextBuildError as caught:
            context_timing = McpContextTiming(
                context_timing.context,
                caught.traces,
            )
            error = str(caught)
            if access_ms == 0.0 and not tool_calling_mode:
                access_ms = (time.perf_counter() - started) * 1000
        except (
            McpAdapterError,
            McpHttpError,
            McpProtocolError,
            McpToolCallValidationError,
            McpTransportError,
            NimRequestError,
            NimTransportError,
        ) as caught:
            error = str(caught)
            if access_ms == 0.0 and not tool_calling_mode:
                access_ms = (time.perf_counter() - started) * 1000
        _write_record(
            stream,
            _record(
                benchmark_case,
                repeat,
                turn,
                question,
                context_timing,
                access_ms,
                answer,
                model_ttft_ms,
                model_total_ms,
                error,
                metadata,
            ),
        )
        if retrieval_only and not answer:
            history.append(ChatMessage(role="user", content=question))
        elif answer:
            history.extend(
                (
                    ChatMessage(role="user", content=question),
                    ChatMessage(role="assistant", content=answer),
                )
            )


def _record(
    benchmark_case: BenchmarkCase,
    repeat: int,
    turn: int,
    question: str,
    timing: McpContextTiming,
    access_ms: float,
    answer: str,
    model_ttft_ms: float | None,
    model_total_ms: float | None,
    error: str | None,
    metadata: BenchmarkMetadata | None = None,
) -> BenchmarkRecord:
    trace = _combined_trace(timing.traces)
    return BenchmarkRecord(
        benchmark_case.case_id,
        repeat,
        turn,
        "mcp-live",
        question,
        answer,
        timing.context.source_paths,
        timing.context.retrieved_count,
        timing.context.tool_calls,
        len(timing.context.text),
        access_ms,
        model_ttft_ms,
        model_total_ms,
        None if model_ttft_ms is None else access_ms + model_ttft_ms,
        None if model_total_ms is None else access_ms + model_total_ms,
        False,
        error,
        0.0,
        0.0,
        trace.http_requests,
        trace.page_count,
        trace.retry_count,
        trace.rate_limit_count,
        sum(item.elapsed_ms for item in timing.traces),
        tuple(f"{item.operation}:{item.tool_name}" for item in timing.traces),
        metadata=metadata,
    )


def _combined_trace(traces: tuple[McpToolTrace, ...]) -> McpToolTrace:
    return McpToolTrace(
        "mcp-live",
        "notion-search/notion-fetch",
        sum(trace.elapsed_ms for trace in traces),
        sum(trace.http_requests for trace in traces),
        sum(trace.retry_count for trace in traces),
        sum(trace.rate_limit_count for trace in traces),
        sum(trace.page_count for trace in traces),
    )


def _scope(
    workspace_id: str, allowed_page_ids: str, active_snapshot_id: str | None
) -> McpScope:
    normalized_workspace_id = workspace_id.strip()
    page_ids = frozenset(
        item.strip() for item in allowed_page_ids.split(",") if item.strip()
    )
    if not normalized_workspace_id:
        raise typer.BadParameter("NOTION_MCP_WORKSPACE_ID is required")
    if not page_ids:
        raise typer.BadParameter(
            "NOTION_MCP_ALLOWED_PAGE_IDS must contain at least one page ID"
        )
    return McpScope(normalized_workspace_id, active_snapshot_id, page_ids)


def _select_cases(
    cases: tuple[BenchmarkCase, ...], selection: str | None
) -> tuple[BenchmarkCase, ...]:
    if selection is None:
        return cases
    wanted = tuple(item.strip() for item in selection.split(",") if item.strip())
    case_by_id = {item.case_id: item for item in cases}
    missing = tuple(case_id for case_id in wanted if case_id not in case_by_id)
    if missing:
        raise typer.BadParameter(f"unknown case ID(s): {', '.join(missing)}")
    return tuple(case_by_id[case_id] for case_id in wanted)


def _previous_questions(history: list[ChatMessage]) -> tuple[str, ...]:
    return tuple(message.content for message in history if message.role == "user")


def _validate_mcp_settings(settings: McpSettings) -> None:
    if not settings.access_token.get_secret_value():
        raise McpTransportError("NOTION_MCP_ACCESS_TOKEN is required")


def _validate_nim_settings(settings: NimSettings) -> None:
    if not settings.api_key:
        raise NimConfigurationError("api_key")
    if not settings.model:
        raise NimConfigurationError("model")


if __name__ == "__main__":
    try:
        typer.run(main)
    except (
        GoldSetError,
        McpHttpError,
        McpProtocolError,
        McpTransportError,
        NimConfigurationError,
    ) as error:
        Console().print(f"[red]{error}[/red]")
        raise typer.Exit(code=2) from error
