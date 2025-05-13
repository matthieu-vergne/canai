package fr.vergne.canai;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Main {

	private static final Consumer<Supplier<Object>> LOGGER = obj -> System.out.println(obj.get());
	private static final Consumer<Supplier<Object>> LOGGERR = obj -> System.err.println(obj.get());

	public static void main(String[] args) throws JsonMappingException, JsonProcessingException {
		String uri = args[0];
		String model = args[1];
		String systemContent = args[2];
		String userContent = args[3];
		Duration timeout = Duration.ofSeconds(30);

		URI openAiUri = toOpenAiUri(uri);

		// trial1(model, systemContent, userContent, timeout, openAiUri);
		{
			Map<String, Function<Map<String, String>, String>> functions;

			String jsonRequest;
			JsonNodeFactory factory = JsonNodeFactory.instance;

			ObjectNode systemNode = factory.objectNode()//
					.put("role", "system")//
					.put("content", systemContent);

			ObjectNode userNode = factory.objectNode()//
					.put("role", "user")//
					.put("content", userContent);

			ObjectNode toolNode = factory.objectNode();
			toolNode.put("type", "function");
			ObjectNode functionNode = toolNode.putObject("function");
			Function<Map<String, String>, String> function = functionArgs -> {
				return "Time: now, Weather: extreme snowstorm, Temperature: -13°C, Confidence: reliable";
			};
			String functionName = "get_realtime_wheather";
			functions = new HashMap<>();
			functions.put(functionName, function);
			functionNode.put("name", functionName);
			functionNode.put("description", "Get precise wheather and temperature for a given location.");
			ObjectNode parametersNode = functionNode.putObject("parameters");
			parametersNode.put("type", "object");
			ObjectNode propertiesNode = parametersNode.putObject("properties");
			ObjectNode locationNode = propertiesNode.putObject("location");
			locationNode.put("type", "string");
			locationNode.put("description", "City and country e.g. Bogotá, Colombia");
			parametersNode.putArray("required").add("location");
			parametersNode.put("additionalProperties", false);

			ObjectNode requestNode = factory.objectNode();
			requestNode.put("model", model);
			ArrayNode messagesNode = requestNode.putArray("messages");
			messagesNode//
					.add(systemNode)//
					.add(userNode);
			requestNode.putArray("tools").add(toolNode);
			requestNode.put("max_tokens", -1);
			requestNode.put("stream", false);
			LOGGER.accept(() -> "Request: " + requestNode.toPrettyString());
			jsonRequest = requestNode.toString();

			HttpRequest httpRequest = createHttpJsonRequest(openAiUri, jsonRequest, timeout);
			HttpResponse<String> httpResponse = resolveHttpJsonResponse(httpRequest);
			String jsonResponse = resolveJsonResponse(httpResponse);

			processResponse(jsonResponse, timeout, openAiUri, functions, factory, requestNode, messagesNode);
		}
	}

	private static void processResponse(String jsonResponse, Duration timeout, URI openAiUri,
			Map<String, Function<Map<String, String>, String>> functions, JsonNodeFactory factory,
			ObjectNode requestNode, ArrayNode messagesNode) throws JsonProcessingException, JsonMappingException {
		JsonMapper mapper = new JsonMapper();
		JsonNode bodyNode = mapper.readTree(jsonResponse);

		if (bodyNode.has("error")) {
			String errorContent = bodyNode.get("error").textValue();
			LOGGER.accept(() -> "Error: " + errorContent);
		} else {
			JsonNode messageNode = bodyNode.get("choices").get(0).get("message");
			if (messageNode.has("content")) {
				String responseContent = messageNode.get("content").textValue();
				LOGGER.accept(() -> "Content: " + responseContent);
			} else if (messageNode.has("tool_calls")) {
				JsonNode toolCallNode = messageNode.get("tool_calls").get(0);
				JsonNode funcNode = toolCallNode.get("function");
				String funcName = funcNode.get("name").textValue();
				JsonNode argsNode = mapper.readTree(funcNode.get("arguments").textValue());
				Set<Entry<String, JsonNode>> funcArgs = argsNode.properties();
				LOGGER.accept(() -> "Call: " + funcName + funcArgs);

				if (functions.containsKey(funcName)) {
					Map<String, String> fArgs = new HashMap<String, String>();
					for (Entry<String, JsonNode> entry : funcArgs) {
						String key = entry.getKey();
						String value = entry.getValue().textValue();
						fArgs.put(key, value);
					}
					String result = functions.get(funcName).apply(fArgs);
					LOGGER.accept(() -> "Result: " + result);

					ObjectNode assistantNode = factory.objectNode()//
							.put("role", "assistant")//
							.putPOJO("function_call", funcNode);

					ObjectNode resultNode = factory.objectNode()//
							.put("role", "tool")//
							.put("content", result);

					messagesNode.add(assistantNode);
					messagesNode.add(resultNode);
					requestNode.remove("tools");// Force not calling more tools
					LOGGER.accept(() -> "Request: " + requestNode.toPrettyString());
					String jsonRequest2 = requestNode.toString();

					HttpRequest httpRequest2 = createHttpJsonRequest(openAiUri, jsonRequest2, timeout);
					HttpResponse<String> httpResponse2 = resolveHttpJsonResponse(httpRequest2);
					String jsonResponse2 = resolveJsonResponse(httpResponse2);
					processResponse(jsonResponse2, timeout, openAiUri, functions, factory, requestNode, messagesNode);
				} else {
					LOGGERR.accept(() -> "Not supported function " + funcName + ", display response");
					LOGGER.accept(() -> "Response: " + bodyNode.toPrettyString());
				}
			} else {
				LOGGERR.accept(() -> "Not supported response, display it");
				LOGGER.accept(() -> "Response: " + bodyNode.toPrettyString());
			}
		}
	}

	private static void trial1(String model, String systemContent, String userContent, Duration timeout, URI openAiUri)
			throws JsonProcessingException, JsonMappingException {
		String jsonRequest = createJsonRequest(model, systemContent, userContent);
		HttpRequest httpRequest = createHttpJsonRequest(openAiUri, jsonRequest, timeout);
		HttpResponse<String> httpResponse = resolveHttpJsonResponse(httpRequest);
		String jsonResponse = resolveJsonResponse(httpResponse);

		LOGGER.accept(() -> "Response: " + jsonResponse);
		JsonMapper mapper = new JsonMapper();
		JsonNode bodyNode = mapper.readTree(jsonResponse);
		String responseContent = bodyNode.get("choices").get(0).get("message").get("content").textValue();
		LOGGER.accept(() -> "Content: " + responseContent);
	}

	private static String resolveJsonResponse(HttpResponse<String> response) {
		return response.body();
	}

	private static HttpResponse<String> resolveHttpJsonResponse(HttpRequest request) {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException | IOException cause) {
			throw new RuntimeException(cause);
		}
	}

	private static HttpRequest createHttpJsonRequest(URI openAiUri, String requestBody, Duration timeout) {
		return HttpRequest.newBuilder()//
				.version(HttpClient.Version.HTTP_1_1)//
				.uri(openAiUri)//
				.header("Content-Type", "application/json")//
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))//
				.timeout(timeout)//
				.build();
	}

	private static URI toOpenAiUri(String uri) {
		try {
			URI baseUri = new URI(uri);
			return new URI(//
					baseUri.getScheme(), //
					baseUri.getUserInfo(), //
					baseUri.getHost(), //
					baseUri.getPort(), //
					"/v1/chat/completions", //
					null, null);
		} catch (URISyntaxException cause) {
			throw new RuntimeException(cause);
		}
	}

	private static String createJsonRequest(String model, String systemContent, String userContent) {
		JsonNodeFactory factory = JsonNodeFactory.instance;

		ObjectNode systemNode = factory.objectNode()//
				.put("role", "system")//
				.put("content", systemContent);

		ObjectNode userNode = factory.objectNode()//
				.put("role", "user")//
				.put("content", userContent);

		ObjectNode requestNode = factory.objectNode();
		requestNode.put("model", model);
		requestNode.putArray("messages").add(systemNode).add(userNode);
		requestNode.put("temperature", 0.7);
		requestNode.put("max_tokens", -1);
		requestNode.put("stream", false);

//		LOGGER.accept(() -> "Request: " + requestNode.toPrettyString());

		return requestNode.toString();
	}

}
