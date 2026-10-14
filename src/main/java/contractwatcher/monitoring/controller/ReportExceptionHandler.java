package contractwatcher.monitoring.controller;

import contractwatcher.monitoring.exception.ReportValidationException;
import contractwatcher.monitoring.exception.ReportHistoryRequestException;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ProblemDetail> unsupportedMediaType(HttpServletRequest request) {
		return problem(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
				"Требуется Content-Type application/json.", Map.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> processingFailure(Exception exception, HttpServletRequest request) {
		if (storageUnavailable(exception)) {
			return problem(request, HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE",
					"Хранилище отчетов временно недоступно.", Map.of());
		}
		return problem(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"GET".equals(request.getMethod()) ? "Не удалось прочитать историю." : "Не удалось обработать отчет.", Map.of());
	}

	private boolean storageUnavailable(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			// Ошибка rollback может скрыть исходную потерю соединения.
			if (cause instanceof TransactionSystemException transaction
					&& transaction.getApplicationException() != null
					&& storageUnavailable(transaction.getApplicationException())) {
				return true;
			}
			if (cause instanceof DataAccessResourceFailureException
					|| cause instanceof TransientDataAccessResourceException) {
				return true;
			}
			if (cause instanceof SQLException sql && sql.getSQLState() != null
					&& (sql.getSQLState().startsWith("08")
							|| List.of("57P01", "57P02", "57P03").contains(sql.getSQLState()))) {
				return true;
			}
		}
		return false;
	}

	private ResponseEntity<ProblemDetail> problem(HttpServletRequest request, HttpStatus status, String code, String detail,
			Map<String, List<String>> errors) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("about:blank"));
		problem.setTitle(status.getReasonPhrase());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code);
		problem.setProperty("errors", errors);
		LOG.warn("{} {}: status={}, code={}", request.getMethod(),
				request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), status.value(), code);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}
}
