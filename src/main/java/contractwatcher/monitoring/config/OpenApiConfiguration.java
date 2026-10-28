package contractwatcher.monitoring.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import contractwatcher.monitoring.dto.ApiSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	@Bean
	OpenAPI monitoringApi() {
		var components = new Components();
		return new OpenAPI().openapi("3.0.3").components(components).security(List.of()).servers(List.of(new Server().url("/")))
				.info(new Info().title("ContractWatch Monitoring").version("1.0.0").description(
						"Внутренний API хранения отчетов. Проверка credentials в Monitoring пока отсутствует. "
						+ "UUID: полная запись 8-4-4-4-12, любой регистр hex и нулевой UUID допустимы; проверяются до БД. "
						+ "receivedAt и времена групп/сводки основаны на report.timestamp SDK; прежние имена полей сохранены. "
						+ "Время ответа: UTC/Z, дробь до 6 знаков. Старые записи сохраняют прежнее время. Nullable-поля ответа присутствуют явно. "
						+ "Счетчики: точные JSON-целые int64; в JavaScript выше 9007199254740991 нужен парсер без потери точности. "
						+ "Прежние GET игнорируют неизвестные query; массовый POST сводок отклоняет непустой query. Отдельные HTTP-запросы читают независимые снимки."))
				.tags(List.of(new Tag().name("Отчеты").description("Прием, история и сохраненные нарушения."),
						new Tag().name("Группы нарушений").description("Группы и число появлений нарушений."),
						new Tag().name("Сводка").description("Счетчики сохраненных отчетов интеграции.")));
	}

	@Bean
	OpenApiCustomizer monitoringContracts() {
		return api -> {
			api.getComponents().addSchemas("Problem", problemSchema());
			api.getComponents().getSchemas().values().forEach(OpenApiConfiguration::scalarConstraints);
			api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
				if (operation.getParameters() != null) {
					operation.getParameters().stream().filter(parameter -> "path".equals(parameter.getIn())).forEach(parameter -> {
						parameter.setSchema(new StringSchema().format("uuid").pattern(ApiSchema.UUID_PATTERN)
								.minLength(36).maxLength(36));
						parameter.setDescription("UUID в полной записи; любой регистр hex, включая нулевой UUID. Проверяется до БД.");
						parameter.setExample(identifier(parameter.getName()));
					});
				}
				errors(path, operation);
			}));
		};
	}

	private static void scalarConstraints(Schema<?> schema) {
		if (Boolean.TRUE.equals(schema.getNullable()) && schema.getEnum() != null && !schema.getEnum().contains(null)) {
			schema.getEnum().add(null);
		}
		if ("uuid".equals(schema.getFormat())) {
			schema.setPattern(ApiSchema.UUID_PATTERN);
			schema.setMinLength(36);
			schema.setMaxLength(36);
		} else if ("date-time".equals(schema.getFormat()) && schema.getPattern() == null) {
			schema.setPattern(ApiSchema.UTC_PATTERN);
		} else if ("int64".equals(schema.getFormat())) {
			schema.setMaximum(new BigDecimal(ApiSchema.MAX_LONG));
		} else if ("int32".equals(schema.getFormat())) {
			if (schema.getMaximum() == null) {
				schema.setMaximum(BigDecimal.valueOf(Integer.MAX_VALUE));
			}
		}
		if (schema.getProperties() != null) {
			schema.getProperties().values().forEach(OpenApiConfiguration::scalarConstraints);
		}
		if (schema.getItems() != null) {
			scalarConstraints(schema.getItems());
		}
	}

	private static Schema<?> problemSchema() {
		// code и errors являются расширениями ProblemDetail и не выводятся из его Java-типа.
		return new ObjectSchema().additionalProperties(false)
				.required(List.of("type", "title", "status", "detail", "instance", "code", "errors"))
				.addProperty("type", new StringSchema().format("uri")._enum(List.of("about:blank")))
				.addProperty("title", new StringSchema())
				.addProperty("status", new IntegerSchema().format("int32"))
				.addProperty("detail", new StringSchema())
				.addProperty("instance", new StringSchema().format("uri-reference").description("Путь запроса без query."))
				.addProperty("code", new StringSchema()._enum(List.of("INVALID_REQUEST", "VALIDATION_REPORT_INVALID",
						"REPORT_NOT_FOUND", "VIOLATION_GROUP_NOT_FOUND", "REQUEST_TOO_LARGE", "UNSUPPORTED_MEDIA_TYPE", "STORAGE_UNAVAILABLE", "INTERNAL_ERROR")))
				.addProperty("errors", new ObjectSchema().additionalProperties(new ArraySchema().items(new StringSchema()))
						.description("Имя поля -> массив сообщений; {} для общей ошибки."));
	}

	private static void errors(String path, Operation operation) {
		var details = switch (operation.getOperationId()) {
			case "ingestReport" -> new ErrorDetails("Некорректное тело запроса.", "Не удалось обработать отчет.");
			case "getReportHistory" -> new ErrorDetails("Некорректные параметры истории.", "Не удалось прочитать историю.");
			case "getReportDetails" -> new ErrorDetails("Некорректные идентификаторы отчета.", "Не удалось прочитать отчет.");
			case "getViolationGroups" -> new ErrorDetails("Некорректные параметры списка групп.", "Не удалось прочитать группы нарушений.");
			case "getViolationGroup" -> new ErrorDetails("Некорректные идентификаторы группы нарушений.", "Не удалось прочитать группу нарушений.");
			case "getIntegrationSummary" -> new ErrorDetails("Некорректный идентификатор интеграции.", "Не удалось прочитать сводку интеграции.");
			case "queryIntegrationSummaries" -> new ErrorDetails("Некорректные параметры сводки.", "Не удалось прочитать сводки интеграций.");
			default -> throw new IllegalStateException("Неизвестная документируемая операция.");
		};
		String instance = path.replace("{integrationId}", identifier("integrationId"))
				.replace("{reportId}", identifier("reportId")).replace("{groupId}", identifier("groupId"));
		boolean ingest = "ingestReport".equals(operation.getOperationId());
		boolean summaries = "queryIntegrationSummaries".equals(operation.getOperationId());
		Map<String, List<String>> invalidFields;
		if (summaries) {
			invalidFields = Map.of("integrationIds", List.of("Требуется массив от 0 до 10000 UUID до удаления повторов."));
		} else {
			invalidFields = ingest ? Map.of()
					: Map.of("integrationId", List.of("Требуется UUID в стандартной строковой записи."));
		}
		String invalidInstance = ingest ? instance : instance.replace(identifier("integrationId"), "not-a-uuid");
		var invalid = problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", details.invalid(), invalidInstance, invalidFields);
		if (ingest) {
			invalid.setDescription("INVALID_REQUEST: JSON или типы полей; VALIDATION_REPORT_INVALID: правила отчета.");
			invalid.getContent().get("application/problem+json").addExamples("validation", example(
					HttpStatus.BAD_REQUEST, "VALIDATION_REPORT_INVALID", "Отчет содержит некорректные данные.", instance,
					Map.of("report.violations", List.of("Для Failed требуется хотя бы одно нарушение."))));
			operation.getResponses().addApiResponse("415", problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
					"UNSUPPORTED_MEDIA_TYPE", "Требуется Content-Type application/json.", instance, Map.of()));
		}
		operation.getResponses().addApiResponse("400", invalid);
		if (summaries) {
			operation.getResponses().addApiResponse("413", problem(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE",
					"Тело запроса превышает 524288 байт.", instance, Map.of()));
			operation.getResponses().addApiResponse("415", problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
					"Требуется Content-Type application/json и несжатое тело.", instance, Map.of()));
		}
		if ("getIntegrationSummary".equals(operation.getOperationId())) {
			invalid.setDescription("INVALID_REQUEST: UUID или параметры периода. Ошибка одного UUID сохраняет прежний detail.");
			invalid.getContent().get("application/problem+json").addExamples("invalidPeriod", example(
					HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Некорректные параметры сводки.", instance,
					Map.of("to", List.of("Верхняя граница должна быть позже нижней."))));
		}
		if ("getReportHistory".equals(operation.getOperationId())) {
			invalid.getContent().get("application/problem+json").addExamples("invalidContractId", example(
					HttpStatus.BAD_REQUEST, "INVALID_REQUEST", details.invalid(), instance,
					Map.of("contractId", List.of("Каждое значение должно быть UUID в стандартной строковой записи."))));
			invalid.getContent().get("application/problem+json").addExamples("tooManyContractIds", example(
					HttpStatus.BAD_REQUEST, "INVALID_REQUEST", details.invalid(), instance,
					Map.of("contractId", List.of("Требуется от 1 до 50 вхождений contractId до удаления повторов."))));
		}
		operation.getResponses().addApiResponse("500", problem(HttpStatus.INTERNAL_SERVER_ERROR,
				"INTERNAL_ERROR", details.failure(), instance, Map.of()));
		operation.getResponses().addApiResponse("503", problem(HttpStatus.SERVICE_UNAVAILABLE,
				"STORAGE_UNAVAILABLE", "Хранилище отчетов временно недоступно.", instance, Map.of()));
		if ("getReportDetails".equals(operation.getOperationId())) {
			operation.getResponses().addApiResponse("404", problem(HttpStatus.NOT_FOUND,
					"REPORT_NOT_FOUND", "Отчет не найден в указанной интеграции.", instance, Map.of()));
		} else if ("getViolationGroup".equals(operation.getOperationId())) {
			operation.getResponses().addApiResponse("404", problem(HttpStatus.NOT_FOUND,
					"VIOLATION_GROUP_NOT_FOUND", "Группа нарушений не найдена в указанной интеграции.", instance, Map.of()));
		}
	}

	private static ApiResponse problem(HttpStatus status, String code, String detail, String instance,
			Map<String, List<String>> errors) {
		return new ApiResponse().description(code + ": " + detail).content(new Content().addMediaType(
				"application/problem+json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Problem"))
						.addExamples(code, example(status, code, detail, instance, errors))));
	}

	private static Example example(HttpStatus status, String code, String detail, String instance,
			Map<String, List<String>> errors) {
		return new Example().value(Map.of("type", "about:blank", "title", status.getReasonPhrase(), "status", status.value(),
				"detail", detail, "instance", instance, "code", code, "errors", errors));
	}

	private static String identifier(String name) {
		return switch (name) {
			case "integrationId" -> "22222222-2222-4222-8222-222222222222";
			case "reportId" -> "66666666-6666-4666-8666-666666666666";
			case "groupId" -> "88888888-8888-4888-8888-888888888888";
			default -> throw new IllegalArgumentException("Неизвестный UUID-параметр.");
		};
	}

	private record ErrorDetails(String invalid, String failure) {
	}
}
