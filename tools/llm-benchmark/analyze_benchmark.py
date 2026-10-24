#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = ["numpy", "pydantic", "typer"]
# ///

# ruff: noqa: B008  # Typer uses Option calls as the CLI declaration syntax.

# ─── How to run ───
# 1. Install uv (if not installed):
#      curl -LsSf https://astral.sh/uv/install.sh | sh
# 2. Run directly (no venv, no pip install needed):
#      uv run tools/llm-benchmark/analyze_benchmark.py --help
# 3. Or make executable and run:
#      chmod +x tools/llm-benchmark/analyze_benchmark.py && ./tools/llm-benchmark/analyze_benchmark.py --help
# ──────────────────

from __future__ import annotations

from pathlib import Path
from typing import Final

import typer
from analysis_stats import (
    AnalysisResult,
    MetricSummary,
    ReportError,
    analyze_pairs,
    load_results,
    pair_rows,
)

_DEFAULT_ALPHA: Final[float] = 0.05
_DEFAULT_BOOTSTRAP_ITERATIONS: Final[int] = 10_000
_DEFAULT_PERMUTATION_ITERATIONS: Final[int] = 10_000


def main(
    results: Path = typer.Option(..., help="Benchmark JSONL result file."),
    output: Path = typer.Option(Path("backend/docs/llm-search-ab-test-report.md"), help="Markdown report path."),
    strategy_a: str = typer.Option("raw", help="A strategy label."),
    strategy_b: str = typer.Option("rag", help="B strategy label."),
    alpha: float = typer.Option(_DEFAULT_ALPHA, min=0.001, max=0.2),
    bootstrap_iterations: int = typer.Option(_DEFAULT_BOOTSTRAP_ITERATIONS, min=1),
    permutation_iterations: int = typer.Option(_DEFAULT_PERMUTATION_ITERATIONS, min=1),
) -> None:
    """Analyze paired benchmark latency and write a Markdown report."""
    rows = load_results(results)
    pairs = pair_rows(rows, strategy_a, strategy_b)
    result = analyze_pairs(
        pairs,
        bootstrap_iterations=bootstrap_iterations,
        permutation_iterations=permutation_iterations,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render_report(result, strategy_a, strategy_b, alpha=alpha), encoding="utf-8")
    typer.echo(f"report written: {output}")


def render_report(
    result: AnalysisResult,
    strategy_a: str,
    strategy_b: str,
    *,
    alpha: float = _DEFAULT_ALPHA,
    minimum_pairs: int = 100,
    minimum_unique_cases: int = 30,
) -> str:
    """Render a Markdown report with explicit sample adequacy and uncertainty."""
    status = _status(result, alpha, minimum_pairs, minimum_unique_cases)
    lines = [
        "# Knot LLM A/B latency report",
        "",
        f"- 판정: **{status}**",
        f"- A: `{strategy_a}`",
        f"- B: `{strategy_b}`",
        f"- 유효 paired observations: `{result.pair_count}`",
        f"- 서로 다른 case 수: `{result.unique_case_count}`",
        f"- 유의수준: `{alpha}`",
        f"- 표본 게이트: paired `{minimum_pairs}`개 이상 + 서로 다른 case `{minimum_unique_cases}`개 이상",
        "",
        "## 통계 방법",
        "",
        "같은 case·repeat·turn을 묶은 paired 비교다. 중앙값과 p95는 지연시간의 heavy tail을 보존하고, 차이의 95% 구간은 paired bootstrap, 유의성은 log latency ratio의 sign-flip permutation test로 계산한다. 반복 요청만 늘린 결과는 질문 다양성에 대한 독립 표본으로 간주하지 않는다.",
        "",
        "## 결과",
        "",
    ]
    if result.ttft is None or result.total is None:
        lines.extend(("NIM live 결과가 없어 latency 통계를 계산하지 못했다.", ""))
    else:
        lines.extend(_render_metric("TTFT (end-to-end)", result.ttft))
        lines.extend(_render_metric("Total (end-to-end)", result.total))
    lines.extend(
        (
            "## 실행 전제",
            "",
            "- warm-up 요청은 분석에서 제외한다.",
            "- A/B 순서를 교차·무작위화해 시간대별 NIM 부하 영향을 줄인다.",
            "- 모델, system prompt, temperature, max tokens, snapshot, 질문 순서는 양쪽에서 고정한다.",
            "- 오류·timeout·context limit 초과는 성공 latency에서 제거하지 말고 오류율로 별도 보고한다.",
            "- 현재 골드셋은 13개 case이므로 최소 case 다양성 게이트를 충족하려면 질문을 더 추가해야 한다.",
            "",
        )
    )
    return "\n".join(lines)


def _status(result: AnalysisResult, alpha: float, minimum_pairs: int, minimum_unique_cases: int) -> str:
    if result.pair_count == 0:
        return "BLOCKED — no successful paired model observations"
    if result.pair_count < minimum_pairs or result.unique_case_count < minimum_unique_cases:
        return "INSUFFICIENT SAMPLE — do not claim statistical significance"
    if (
        result.ttft is not None
        and result.ttft.permutation_p_value < alpha
        and result.ttft.median_delta_ci_high < 0
        and result.ttft.b_under_5s >= result.ttft.a_under_5s
    ):
        return "PASS — B is faster on primary TTFT under the configured gate"
    return "NO SIGNIFICANT WIN — keep the current architecture undecided"


def _render_metric(label: str, summary: MetricSummary) -> tuple[str, ...]:
    return (
        f"### {label}",
        "",
        f"- A mean / median / p95: `{summary.a_mean:.2f}` / `{summary.a_median:.2f}` / `{summary.a_p95:.2f}` ms",
        f"- B mean / median / p95: `{summary.b_mean:.2f}` / `{summary.b_median:.2f}` / `{summary.b_p95:.2f}` ms",
        f"- p50 delta (B−A), 95% CI: `{summary.median_delta:.2f}` ms (`{summary.median_delta_ci_low:.2f}`, `{summary.median_delta_ci_high:.2f}`)",
        f"- p95 delta (B−A), 95% CI: `{summary.p95_delta:.2f}` ms (`{summary.p95_delta_ci_low:.2f}`, `{summary.p95_delta_ci_high:.2f}`)",
        f"- robust median change: `{summary.relative_median_pct:.2f}%` (95% CI `{summary.relative_median_ci_low:.2f}%` to `{summary.relative_median_ci_high:.2f}%`)",
        f"- paired sign-flip permutation p-value: `{summary.permutation_p_value:.5f}`",
        f"- TTFT ≤ 5 seconds: A `{summary.a_under_5s}/{summary.observations}` ({100 * summary.a_under_5s / summary.observations:.1f}%), B `{summary.b_under_5s}/{summary.observations}` ({100 * summary.b_under_5s / summary.observations:.1f}%)",
        "",
    )


if __name__ == "__main__":
    try:
        typer.run(main)
    except ReportError as error:
        typer.echo(str(error), err=True)
        raise typer.Exit(code=2) from error
