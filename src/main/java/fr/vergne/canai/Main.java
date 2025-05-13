package fr.vergne.canai;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Main {

	private static final Consumer<Supplier<Object>> LOGGER = obj -> System.out.println(obj.get());

	public static void main(String[] args) throws IOException, InterruptedException, URISyntaxException {
		String uri = args[0];
		String model = args[1];
		String systemContent = args[2];
		String userContent = args[3];

		URI baseUri = new URI(uri);
		URI openAiUri = new URI(baseUri.getScheme(), baseUri.getUserInfo(), baseUri.getHost(), baseUri.getPort(),
				"/v1/chat/completions", null, null);
		String requestBody = buildJsonRequest(model, systemContent, userContent);
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpRequest request = HttpRequest.newBuilder()//
					.version(HttpClient.Version.HTTP_1_1)//
					.uri(openAiUri)//
					.header("Content-Type", "application/json")//
					.POST(HttpRequest.BodyPublishers.ofString(requestBody))//
					.timeout(Duration.ofSeconds(30))//
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			String body = response.body();
			LOGGER.accept(() -> "Response: " + body);
			JsonMapper mapper = new JsonMapper();
			JsonNode bodyNode = mapper.readTree(body);
			String responseContent = bodyNode.get("choices").get(0).get("message").get("content").textValue();
			LOGGER.accept(() -> "Content: " + responseContent);
		}
	}

	private static String buildJsonRequest(String model, String systemContent, String userContent) {
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
