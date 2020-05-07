package io.quarkus.swaggerui.deployment;

import static java.lang.String.format;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.configuration.ConfigurationError;
import io.quarkus.deployment.index.ClassPathArtifactResolver;
import io.quarkus.deployment.index.ResolvedArtifact;
import io.quarkus.deployment.util.FileUtil;
import io.quarkus.smallrye.openapi.common.deployment.SmallRyeOpenApiConfig;
import io.quarkus.swaggerui.runtime.SwaggerUiRecorder;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.deployment.devmode.NotFoundPageDisplayableEndpointBuildItem;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public class SwaggerUiProcessor {

    private static final Logger log = Logger.getLogger(SwaggerUiProcessor.class.getName());

    private static final String SWAGGER_UI_WEBJAR_GROUP_ID = "org.webjars";
    private static final String SWAGGER_UI_WEBJAR_ARTIFACT_ID = "swagger-ui";
    private static final String SWAGGER_UI_WEBJAR_PREFIX = "META-INF/resources/webjars/swagger-ui";
    private static final String SWAGGER_UI_FINAL_DESTINATION = "META-INF/swagger-ui-files";
    private static final Pattern SWAGGER_UI_CONFIG_PATTERN = Pattern.compile(
            "(.*SwaggerUIBundle\\()(.*)(presets:.*)(layout:.*)(\\).*)",
            Pattern.DOTALL);
    private static final String TEMP_DIR_PREFIX = "quarkus-swagger-ui_" + System.nanoTime();

    private static ObjectMapper objectMapper;

    /**
     * The configuration for Swagger UI.
     */
    SwaggerUiConfig swaggerUiConfig;

    /**
     * The configuration for OpenAPI.
     */
    SmallRyeOpenApiConfig openApiConfig;

    @Inject
    private LaunchModeBuildItem launch;

    @BuildStep
    void feature(BuildProducer<FeatureBuildItem> feature) {
        if (swaggerUiConfig.enable && (launch.getLaunchMode().isDevOrTest() || swaggerUiConfig.alwaysInclude)) {
            feature.produce(new FeatureBuildItem(FeatureBuildItem.SWAGGER_UI));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    public void registerSwaggerUiServletExtension(SwaggerUiRecorder recorder,
            BuildProducer<RouteBuildItem> routes,
            BeanContainerBuildItem container,
            BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<NativeImageResourceBuildItem> nativeImageResourceBuildItemBuildProducer,
            LiveReloadBuildItem liveReloadBuildItem,
            HttpRootPathBuildItem httpRootPathBuildItem,
            BuildProducer<NotFoundPageDisplayableEndpointBuildItem> displayableEndpoints) throws Exception {

        if ("/".equals(swaggerUiConfig.path)) {
            throw new ConfigurationError(
                    "quarkus.swagger-ui.path was set to \"/\", this is not allowed as it blocks the application from serving anything else.");
        }

        if (!swaggerUiConfig.enable) {
            return;
        }

        String openApiPath = httpRootPathBuildItem.adjustPath(openApiConfig.path);
        if (launch.getLaunchMode().isDevOrTest()) {
            CachedSwaggerUI cached = liveReloadBuildItem.getContextObject(CachedSwaggerUI.class);

            boolean extractionNeeded = cached == null;
            if (cached != null && !cached.cachedOpenAPIPath.equals(openApiPath)) {
                try {
                    FileUtil.deleteDirectory(Paths.get(cached.cachedDirectory));
                } catch (IOException e) {
                    log.error("Failed to clean Swagger UI temp directory on restart", e);
                }
                extractionNeeded = true;
            }
            if (extractionNeeded) {
                if (cached == null) {
                    cached = new CachedSwaggerUI();
                    liveReloadBuildItem.setContextObject(CachedSwaggerUI.class, cached);
                    Runtime.getRuntime().addShutdownHook(new Thread(cached, "Swagger UI Shutdown Hook"));
                }
                try {
                    ResolvedArtifact artifact = getSwaggerUiArtifact();
                    Path tempDir = Files.createTempDirectory(TEMP_DIR_PREFIX).toRealPath();
                    extractSwaggerUi(artifact, tempDir);
                    updateSwaggerUiConfig(tempDir.resolve("index.html"), openApiPath);
                    cached.cachedDirectory = tempDir.toAbsolutePath().toString();
                    cached.cachedOpenAPIPath = openApiPath;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            Handler<RoutingContext> handler = recorder.handler(cached.cachedDirectory,
                    httpRootPathBuildItem.adjustPath(swaggerUiConfig.path));
            routes.produce(new RouteBuildItem(swaggerUiConfig.path, handler));
            routes.produce(new RouteBuildItem(swaggerUiConfig.path + "/*", handler));
            displayableEndpoints.produce(new NotFoundPageDisplayableEndpointBuildItem(swaggerUiConfig.path + "/"));
        } else if (swaggerUiConfig.alwaysInclude) {
            ResolvedArtifact artifact = getSwaggerUiArtifact();
            //we are including in a production artifact
            //just stick the files in the generated output
            //we could do this for dev mode as well, but then we need to extract them every time
            File artifactFile = artifact.getArtifactPath().toFile();
            try (JarFile jarFile = new JarFile(artifactFile)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                String versionedSwaggerUiWebjarPrefix = format("%s/%s/", SWAGGER_UI_WEBJAR_PREFIX, artifact.getVersion());
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith(versionedSwaggerUiWebjarPrefix) && !entry.isDirectory()) {
                        try (InputStream inputStream = jarFile.getInputStream(entry)) {
                            String filename = entry.getName().replace(versionedSwaggerUiWebjarPrefix, "");
                            byte[] content = FileUtil.readFileContents(inputStream);
                            if (entry.getName().endsWith("index.html")) {
                                final String html = updateConfig(new String(content, StandardCharsets.UTF_8), openApiPath);
                                if (html != null) {
                                    content = html.getBytes(StandardCharsets.UTF_8);
                                }
                            }
                            String fileName = SWAGGER_UI_FINAL_DESTINATION + "/" + filename;
                            generatedResources
                                    .produce(new GeneratedResourceBuildItem(fileName,
                                            content));
                            nativeImageResourceBuildItemBuildProducer.produce(new NativeImageResourceBuildItem(fileName));

                        }
                    }
                }
            }

            Handler<RoutingContext> handler = recorder
                    .handler(SWAGGER_UI_FINAL_DESTINATION, httpRootPathBuildItem.adjustPath(swaggerUiConfig.path));
            routes.produce(new RouteBuildItem(swaggerUiConfig.path, handler));
            routes.produce(new RouteBuildItem(swaggerUiConfig.path + "/*", handler));
        }
    }

    private ResolvedArtifact getSwaggerUiArtifact() {
        ClassPathArtifactResolver resolver = new ClassPathArtifactResolver(SwaggerUiProcessor.class.getClassLoader());
        return resolver.getArtifact(SWAGGER_UI_WEBJAR_GROUP_ID, SWAGGER_UI_WEBJAR_ARTIFACT_ID, null);
    }

    private void extractSwaggerUi(ResolvedArtifact artifact, Path resourceDir) throws IOException {
        File artifactFile = artifact.getArtifactPath().toFile();
        try (JarFile jarFile = new JarFile(artifactFile)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            String versionedSwaggerUiWebjarPrefix = format("%s/%s/", SWAGGER_UI_WEBJAR_PREFIX, artifact.getVersion());
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith(versionedSwaggerUiWebjarPrefix) && !entry.isDirectory()) {
                    try (InputStream inputStream = jarFile.getInputStream(entry)) {
                        String filename = entry.getName().replace(versionedSwaggerUiWebjarPrefix, "");
                        Files.copy(inputStream, resourceDir.resolve(filename));
                    }
                }
            }
        }
    }

    private void updateSwaggerUiConfig(Path indexHtml, String openApiPath) throws IOException {
        String content = new String(Files.readAllBytes(indexHtml), StandardCharsets.UTF_8);
        String result = updateConfig(content, openApiPath);
        if (result != null) {
            Files.write(indexHtml, result.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String updateConfig(String original, String openApiPath) throws JsonProcessingException {
        Matcher configMatcher = SWAGGER_UI_CONFIG_PATTERN.matcher(original);
        if (configMatcher.matches()) {
            updateApiUrl(openApiPath);
            initObjectMapper();
            String defaultConfig = configMatcher.group(3).trim();
            String config;
            String html;
            config = objectMapper.writeValueAsString(swaggerUiConfig.getSwaggerUiProps());
            html = configMatcher.replaceFirst("$1" + buildStringConfig(defaultConfig, config) + "$5");
            if (swaggerUiConfig.oauth.enable) {
                html = addOauthConfig(html, swaggerUiConfig.oauth.getConfigParameters());
            }
            return html;
        } else {
            log.warn("Unable to replace the default configuration of Swagger UI");
            return null;
        }
    }

    private void updateApiUrl(String openApiPath) {
        if (!StringUtils.isEmpty(openApiPath)) {
            final Optional<URI> openApiUri = Optional.of(URI.create(openApiPath));
            if (!swaggerUiConfig.url.isPresent()) {
                swaggerUiConfig.url = openApiUri;
            }
        }
    }

    private String buildStringConfig(String defaultConfig, String config) {
        StringBuilder sb = new StringBuilder(config);
        sb.insert(1, defaultConfig);
        sb.insert(1, "\n");
        return sb.toString();
    }

    private String addOauthConfig(String html, Map<String, Object> oauthParams) throws JsonProcessingException {
        if (oauthParams.isEmpty()) {
            return html;
        }
        StringBuilder sb = new StringBuilder("window.ui = ui\n");
        sb.append("ui.initOAuth(\n");
        String json = objectMapper.writeValueAsString(oauthParams);
        sb.append(json);
        sb.append("\n)");
        return html.replace("window.ui = ui", sb.toString());
    }

    private void initObjectMapper() {
        objectMapper = new JsonMapper().rebuild()
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .serializationInclusion(JsonInclude.Include.NON_ABSENT)
                .disable(JsonWriteFeature.QUOTE_FIELD_NAMES)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .addModule(new Jdk8Module())
                .build();
    }

    private static final class CachedSwaggerUI implements Runnable {

        String cachedOpenAPIPath;
        String cachedDirectory;

        @Override
        public void run() {
            try {
                FileUtil.deleteDirectory(Paths.get(cachedDirectory));
            } catch (IOException e) {
                log.error("Failed to clean Swagger UI temp directory on shutdown", e);
            }
        }
    }
}
