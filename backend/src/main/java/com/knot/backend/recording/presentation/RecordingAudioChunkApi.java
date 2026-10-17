package com.knot.backend.recording.presentation;

import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.config.OpenApiConfig;
import com.knot.backend.global.response.ErrorResponse;
import com.knot.backend.recording.presentation.dto.request.RecordingAudioChunkRequest;
import com.knot.backend.recording.presentation.dto.request.RecordingControlRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioChunkReservationResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioChunkCompletionResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioSaveResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Tag(name = "녹음", description = "본인 녹음 세션")
@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
public interface RecordingAudioChunkApi {
    // @formatter:off
    @Operation(
            summary = "PCM 오디오 조각 예약",
            description = "봉인한 PCM16LE·16kHz·mono 조각을 연속 번호와 offset으로 예약한다. "
                    + "RECORDING/PAUSED의 최초 탭만 변경할 수 있다. 같은 descriptor 재요청은 같은 예약으로 "
                    + "수렴한다. requiredHeaders 그대로 PUT하되 Content-Length와 Host는 브라우저가 설정한다. "
                    + "이미 VERIFIED이면 URL을 발급하지 않는다. 조각 요청은 heartbeat를 갱신하지 않는다.",
            parameters = @Parameter(
                    name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(type = "string"),
                    description = "CSRF 토큰"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새 조각 예약",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingAudioChunkReservationResponse.class))),
            @ApiResponse(responseCode = "200", description = "동일 예약 재요청 또는 이미 VERIFIED",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingAudioChunkReservationResponse.class))),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 또는 INVALID_AUDIO_CHUNK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Workspace 소속·소유자·최초 탭 제어 권한 또는 CSRF 검증 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace·녹음 또는 AUDIO_CHUNK_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "AUDIO_MODE_CONFLICT, AUDIO_CHUNK_CONFLICT, "
                    + "AUDIO_SEQUENCE_GAP(expectedNextSequence 포함), AUDIO_SAVE_FROZEN, "
                    + "AUDIO_CHUNK_NOT_STORED, AUDIO_CHUNK_MISMATCH 또는 종료·폐기 상태",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "AUDIO_STORAGE_UNAVAILABLE: 저장 여부 미확정, 같은 identity로 재시도",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<RecordingAudioChunkReservationResponse> reserve(
            long workspaceId,
            long recordingId,
            @Valid RecordingAudioChunkRequest request,
            @Parameter(hidden = true) AuthenticatedMember member
    );

    @Operation(
            summary = "PCM 오디오 저장 확인",
            description = "예약 객체의 크기·타입·SHA-256·versionId를 검증하고 DB에 저장한 뒤 VERIFIED를 반환한다. "
                    + "저장소 확인 뒤 권한·폐기·freeze를 다시 검사한다. 이미 VERIFIED인 요청의 재시도는 같은 확정 시각을 "
                    + "반환한다. verifiedThroughFrame은 중간 누락 없는 연속 prefix다.",
            parameters = @Parameter(
                    name = OpenApiConfig.CSRF_TOKEN_HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    schema = @Schema(type = "string"),
                    description = "CSRF 토큰"
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검증·DB 커밋 완료 또는 기존 VERIFIED 재확인",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingAudioChunkCompletionResponse.class))),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 또는 INVALID_AUDIO_CHUNK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Workspace 소속·소유자·최초 탭 제어 권한 또는 CSRF 검증 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace·녹음 또는 AUDIO_CHUNK_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "AUDIO_MODE_CONFLICT, AUDIO_CHUNK_CONFLICT, "
                    + "AUDIO_SEQUENCE_GAP(expectedNextSequence 포함), AUDIO_SAVE_FROZEN, "
                    + "AUDIO_CHUNK_NOT_STORED, AUDIO_CHUNK_MISMATCH 또는 종료·폐기 상태",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "AUDIO_STORAGE_UNAVAILABLE: 저장 여부 미확정, 같은 identity로 재시도",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<RecordingAudioChunkCompletionResponse> complete(
            long workspaceId,
            long recordingId,
            @Parameter(description = "예약한 조각 번호", schema = @Schema(minimum = "1")) int sequence,
            @Valid RecordingControlRequest request,
            @Parameter(hidden = true) AuthenticatedMember member
    );

    @Operation(
            summary = "오디오 조각 저장 상태 조회",
            description = "본인과 현재 Workspace 소속을 확인하고 저장 상태를 조회한다. 최초 탭 제어 증명은 필요하지 않다. 조회는 "
                    + "heartbeat와 만료 상태를 변경하지 않는다. verifiedRanges는 start 포함·endExclusive "
                    + "제외의 frame 구간이며 뒤쪽만 저장된 구간도 포함한다. R1 단계에서는 READY를 반환하지 않고 최종화 관련 "
                    + "필드는 아직 확정되지 않는다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 상태와 연속 저장량",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RecordingAudioSaveResponse.class))),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 또는 INVALID_AUDIO_CHUNK",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Workspace 소속·소유자·최초 탭 제어 권한 또는 CSRF 검증 실패",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Workspace·녹음 또는 AUDIO_CHUNK_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "AUDIO_MODE_CONFLICT, AUDIO_CHUNK_CONFLICT, "
                    + "AUDIO_SEQUENCE_GAP(expectedNextSequence 포함), AUDIO_SAVE_FROZEN, "
                    + "AUDIO_CHUNK_NOT_STORED, AUDIO_CHUNK_MISMATCH 또는 종료·폐기 상태",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<RecordingAudioSaveResponse> findState(
            long workspaceId,
            long recordingId,
            @Parameter(hidden = true) AuthenticatedMember member
    );

    // @formatter:on
}
