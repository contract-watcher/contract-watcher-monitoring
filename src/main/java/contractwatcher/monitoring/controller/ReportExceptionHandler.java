package contractwatcher.monitoring.controller;

import contractwatcher.monitoring.exception.ReportValidationException;
import contractwatcher.monitoring.exception.ReportHistoryRequestException;
import contractwatcher.monitoring.exception.ReportDetailsRequestException;
import contractwatcher.monitoring.exception.ReportNotFoundException;
import contractwatcher.monitoring.exception.ViolationGroupsRequestException;
import contractwatcher.monitoring.exception.ViolationGroupDetailsRequestException;
import contractwatcher.monitoring.exception.ViolationGroupNotFoundException;
import contractwatcher.monitoring.exception.IntegrationSummaryRequestException;
import contractwatcher.monitoring.exception.SummaryBodyTooLargeException;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;
import org.springframework.dao.QueryTimeoutException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.servlet.HandlerMapping;

@RestControllerAdvice
final class ReportExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ReportExceptionHandler.class);

	@ExceptionHandler(SummaryBodyTooLargeException.class)
	ResponseEntity<ProblemDetail> summaryTooLarge(HttpServletRequest request) {
		return problem(request, HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE",
				"Тело запроса превышает 524288 байт.", Map.of());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
		return problem(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Некорректное тело запроса.", Map.of());
	}

	@ExceptionHandler(ReportHistoryRequestException.class)
	ResponseEntity<ProblemDetail> invalidHistory(ReportHistoryRequestException exception, HttpServletRequest request) {
		return problem(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), exception.errors());
	}

	@ExceptionHandler(ReportValidationException.class)
	ResponseEntity<ProblemDetail> invalidReport(ReportValidationException exception, HttpServletRequest request) {
		return problem(request, HttpStatus.BAD_REQUEST, "VALIDATION_REPORT_INVALID",
				"Отчет содержит некорректные данные.", exception.errors());
	}

	@ExceptionHandler(IntegrationSummaryRequestException.class)
	ResponseEntity<ProblemDetail> invalidSummary(IntegrationSummaryRequestException exception, HttpServletRequest request) {
		return problem(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), exception.errors());
	}

	@ExceptionHandler(ViolationGroupsRequestException.class)
	ResponseEntity<ProblemDetail> invalidGroups(ViolationGroupsRequestException exception, HttpServletRequest request) {
		return problem(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), exception.errors());
	}

	@ExceptionHandler(ReportDetailsRequestException.class)
	ResponseEntity<ProblemDetail> invalidDetails(ReportDetailsRequestException exception, HttpServletRequest request) {
		return problem(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), exception.errors());
	}

	@ExceptionHandler(ViolationGroupDetailsRequestException.class)
	ResponseEntity<ProblemDetail> invalidGroupDetails(ViolationGroupDetailsRequestException exception,
			HttpServletRequest request) {
		return problem(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), exception.errors());
	}

	@ExceptionHandler(ViolationGroupNotFoundException.class)
	ResponseEntity<ProblemDetail> groupNotFound(ViolationGroupNotFoundException exception, HttpServletRequest request) {
		return problem(request, HttpStatus.NOT_FOUND, "VIOLATION_GROUP_NOT_FOUND", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(ReportNotFoundException.class)
	ResponseEntity<ProblemDetail> reportNotFound(ReportNotFoundException exception, HttpServletRequest request) {
		return problem(request, HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", exception.getMessage(), Map.of());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ProblemDetail> unsupportedMediaType(HttpServletRequest request) {
		return problem(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
				ProjectSummariesController.PATH.equals(request.getRequestURI())
						? "Требуется Content-Type application/json и несжатое тело."
						: "Требуется Content-Type application/json.", Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> processingFailure(Exception exception, HttpServletRequest request) {
		StorageFailure failure = storageFailure(exception);
		request.setAttribute("monitoring.storageFailure", failure);
		if (failure != StorageFailure.NONE) {
			return problem(request, HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
					"Хранилище отчетов временно недоступно.", Map.of());
		}
		return problem(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				failureDetail(request), Map.of());
	}

	private String failureDetail(HttpServletRequest request) {
		if (ProjectSummariesController.PATH.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
			return "Не удалось прочитать сводки интеграций.";
		}
		if ("GET".equals(request.getMethod())) {
			if (IntegrationSummaryController.PATH.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
				return "Не удалось прочитать сводку интеграции.";
			}
			if (ViolationGroupsController.DETAILS_PATH.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
				return "Не удалось прочитать группу нарушений.";
			}
			if (ViolationGroupsController.PATH.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))) {
				return "Не удалось прочитать группы нарушений.";
			}
			return ReportDetailsController.PATH.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
					? "Не удалось прочитать отчет." : "Не удалось прочитать историю.";
		}
		return "Не удалось обработать отчет.";
	}

	private enum StorageFailure { NONE, UNAVAILABLE, TIMEOUT }

	private StorageFailure storageFailure(Throwable exception) {
		var pending = new ArrayDeque<Throwable>();
		var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
		pending.add(exception);
		StorageFailure result = StorageFailure.NONE;
		while (!pending.isEmpty()) {
			Throwable cause = pending.removeFirst();
			if (!visited.add(cause)) { continue; }
			if (cause instanceof QueryTimeoutException || cause instanceof SQLTimeoutException
					|| cause instanceof SQLTransientConnectionException) {
				return StorageFailure.TIMEOUT;
			}
			// Ошибка rollback может скрыть первоначальный сбой хранилища.
			if (cause instanceof TransactionSystemException transaction
					&& transaction.getApplicationException() != null) {
				pending.add(transaction.getApplicationException());
			}
			if (cause instanceof DataAccessResourceFailureException
					|| cause instanceof TransientDataAccessResourceException) {
				result = StorageFailure.UNAVAILABLE;
			}
			if (cause instanceof SQLException sql && sql.getSQLState() != null
					&& (sql.getSQLState().startsWith("08")
							|| List.of("57P01", "57P02", "57P03").contains(sql.getSQLState()))) {
				result = StorageFailure.UNAVAILABLE;
			}
			if (cause.getCause() != null) { pending.add(cause.getCause()); }
		}
		return result;
	}

	private Object safeId(HttpServletRequest request, String name) {
		Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		Object value = variables instanceof Map<?, ?> paths ? paths.get(name) : null;
		if (value instanceof String id && id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
			return UUID.fromString(id);
		}
		Object validated = request.getAttribute("monitoring." + name);
		return validated instanceof UUID ? validated : null;
	}

	private ResponseEntity<ProblemDetail> problem(HttpServletRequest request, HttpStatus status, String code, String detail,
			Map<String, List<String>> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("about:blank"));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("errors", errors);
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		Object failure = request.getAttribute("monitoring.storageFailure");
		var event = status.is5xxServerError() ? LOG.atError() : status == HttpStatus.NOT_FOUND ? LOG.atInfo() : LOG.atWarn();
		event.log("{} {}: status={}, code={}, integrationId={}, reportId={}, groupId={}, storage={}, timeout={}, count={}",
				request.getMethod(), pattern == null ? "unmapped" : pattern, status.value(), code,
				safeId(request, "integrationId"), safeId(request, "reportId"), safeId(request, "groupId"),
				failure == null ? "NONE" : failure, failure == StorageFailure.TIMEOUT,
				request.getAttribute("monitoring.integrationCount"));
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}
}
