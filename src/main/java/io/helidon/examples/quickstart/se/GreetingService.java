
package io.helidon.examples.quickstart.se;

import io.helidon.common.http.Http;
import io.helidon.config.Config;
import io.helidon.integrations.microstream.core.EmbeddedStorageManagerBuilder;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;

import javax.json.*;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple service to greet you. Examples:
 * <p>
 * Get default greeting message:
 * curl -X GET http://localhost:8080/greet
 * <p>
 * Get greeting message for Joe:
 * curl -X GET http://localhost:8080/greet/Joe
 * <p>
 * Change greeting
 * curl -X PUT -H "Content-Type: application/json" -d '{"greeting" : "Howdy"}' http://localhost:8080/greet/greeting
 * <p>
 * Get the logs:
 * curl -X GET http://localhost:8080/greet/logs
 * <p>
 * The message is returned as a JSON object
 */

class GreetingService
    implements Service {

  /**
   * The config value for the key {@code greeting}.
   */
  private final AtomicReference<String> greeting = new AtomicReference<>();

  private static final JsonBuilderFactory JSON = Json.createBuilderFactory(Collections.emptyMap());

  private static final Logger LOGGER = Logger.getLogger(GreetingService.class.getName());

  private final GreetingServiceMicrostreamContext mctx;

  GreetingService(Config config) {
    greeting.set(config.get("app.greeting")
                       .asString()
                       .orElse("Ciao"));

    mctx = new GreetingServiceMicrostreamContext(
        EmbeddedStorageManagerBuilder.create(
            config.get("microstream")));
    // we need to initialize the root element first
    // if we do not wait here, we have a race where HTTP method may be invoked before we initialize root
    mctx.start()
        .await();
    mctx.initRootElement();
  }

  /**
   * A service registers itself by updating the routing rules.
   *
   * @param rules the routing rules.
   */
  @Override
  public void update(Routing.Rules rules) {
    rules
        .get("/", this::getDefaultMessageHandler)
        .get("/logs", this::getLog)
        .get("/{name}", this::getMessageHandler)
        .put("/greeting", this::updateGreetingHandler);
  }

  private void getLog(ServerRequest request,
                      ServerResponse response) {

    mctx.getLogs()
        .thenAccept((logs) -> {
          JsonArrayBuilder arrayBuilder = JSON.createArrayBuilder();
          logs.forEach((entry) -> arrayBuilder.add(
              JSON.createObjectBuilder()
                  .add("name", entry.getName())
                  .add("time", entry.getDateTime()
                                    .toString())
          ));
          response.send(arrayBuilder.build());
        })
        .exceptionally(e -> processErrors(e, request, response));
  }

  /**
   * Return a worldly greeting message.
   *
   * @param request  the server request
   * @param response the server response
   */
  private void getDefaultMessageHandler(ServerRequest request,
                                        ServerResponse response) {
    sendResponse(response, "World");
  }

  /**
   * Return a greeting message using the name that was provided.
   *
   * @param request  the server request
   * @param response the server response
   */
  private void getMessageHandler(ServerRequest request,
                                 ServerResponse response) {
    String name = request.path()
                         .param("name");
    sendResponse(response, name);
  }

  private void sendResponse(ServerResponse response, String name) {
    String msg = String.format("%s %s!", greeting.get(), name);

    mctx.addLogEntry(name);

    JsonObject returnObject = JSON.createObjectBuilder()
                                  .add("message", msg)
                                  .build();
    response.send(returnObject);
  }

  private static <T> T processErrors(Throwable ex, ServerRequest request, ServerResponse response) {

    ex.printStackTrace();

    if (ex.getCause() instanceof JsonException) {

      LOGGER.log(Level.FINE, "Invalid JSON", ex);
      JsonObject jsonErrorObject = JSON.createObjectBuilder()
                                       .add("error", "Invalid JSON")
                                       .build();
      response.status(Http.Status.BAD_REQUEST_400)
              .send(jsonErrorObject);
    } else {

      LOGGER.log(Level.FINE, "Internal error", ex);
      JsonObject jsonErrorObject = JSON.createObjectBuilder()
                                       .add("error", "Internal error")
                                       .build();
      response.status(Http.Status.INTERNAL_SERVER_ERROR_500)
              .send(jsonErrorObject);
    }

    return null;
  }

  private void updateGreetingFromJson(JsonObject jo, ServerResponse response) {

    if (!jo.containsKey("greeting")) {
      JsonObject jsonErrorObject = JSON.createObjectBuilder()
                                       .add("error", "No greeting provided")
                                       .build();
      response.status(Http.Status.BAD_REQUEST_400)
              .send(jsonErrorObject);
      return;
    }

    greeting.set(jo.getString("greeting"));
    response.status(Http.Status.NO_CONTENT_204)
            .send();
  }

  /**
   * Set the greeting to use in future messages.
   *
   * @param request  the server request
   * @param response the server response
   */
  private void updateGreetingHandler(ServerRequest request,
                                     ServerResponse response) {
    request.content()
           .as(JsonObject.class)
           .thenAccept(jo -> updateGreetingFromJson(jo, response))
           .exceptionally(ex -> processErrors(ex, request, response));
  }

}