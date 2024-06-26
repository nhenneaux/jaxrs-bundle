package com.github.nhenneaux.jaxrs.bundle;

import com.github.nhenneaux.jersey.connector.httpclient.HttpClientConnector;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.glassfish.jersey.client.ClientConfig;
import org.hamcrest.Matchers;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("squid:S00112")
class JettyServerTest {
    static final int PORT = 2223;
    private static final String PING = "/ping";

    @BeforeEach
    void setUp(TestInfo testInfo) {
        var testClass = testInfo.getTestClass().orElseThrow();
        var testMethod = testInfo.getTestMethod().orElseThrow();
        System.out.println(testClass.getSimpleName() + "::" + testMethod.getName() + " test has started.");
    }

    @AfterEach
    void tearDown(TestInfo testInfo) {
        var testClass = testInfo.getTestClass().orElseThrow();
        var testMethod = testInfo.getTestMethod().orElseThrow();
        System.out.println(testClass.getSimpleName() + "::" + testMethod.getName() + " test has finished.");
    }

    private static WebTarget getClient(int port, KeyStore trustStore, ClientConfig clientConfig) {
        return ClientBuilder.newBuilder()
                .trustStore(trustStore)
                .withConfig(clientConfig)
                .build()
                .target("https://localhost:" + port);
    }

    private static ClientConfig http2ClientConfig() {
        return new ClientConfig()
                .connectorProvider(HttpClientConnector::new);
    }

    static AutoCloseable jerseyServer(int port, JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration, final Class<?>... serviceClasses) {
        return new JettyServer(port, tlsSecurityConfiguration, serviceClasses);
    }

    static WebTarget getClient(int port) {
        return getClient(port, trustStore(), http2ClientConfig());
    }

    private static KeyStore trustStore() {
        return JettyServer.TlsSecurityConfiguration.getKeyStore("TEST==ONLY==truststore-password".toCharArray(), "truststore.p12");
    }

    static JettyServer.TlsSecurityConfiguration tlsConfig() {
        return new JettyServer.TlsSecurityConfiguration(
                JettyServer.TlsSecurityConfiguration.getKeyStore("TEST==ONLY==key-store-password".toCharArray(), "keystore.p12"),
                "localhost with alternate ip",
                "TEST==ONLY==key-store-password",
                "TLSv1.3"
        );
    }

    @Test
    @Timeout(20)
    void testValidTls() throws Exception {
        int port = PORT;
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            final Response ping = getClient(port).path(PING).request().head();
            assertEquals(204, ping.getStatus());
        }
    }

    @Test
    @Timeout(60)
    void testConcurrent() throws Exception {
        testConcurrent(http2ClientConfig());
    }

    @Test
    @Timeout(60)
    void testConcurrentHttp2JavaHttpClient() throws Exception {
        testConcurrent(new ClientConfig()
                .connectorProvider((jaxRsClient, configuration) -> getHttpClientConnector(jaxRsClient, HttpClient.Version.HTTP_2)));
    }

    @Test
    @Timeout(60)
    void testConcurrentGetHttp2JavaHttpClient() throws Exception {
        testConcurrent(new ClientConfig()
                .connectorProvider((jaxRsClient, configuration) -> getHttpClientConnector(jaxRsClient, HttpClient.Version.HTTP_2)), HttpMethod.GET, "/pingWithSleep");
    }

    @Test
    @Timeout(60)
    void testConcurrentGetDefaultJavaHttpClient() throws Exception {
        testConcurrent(new ClientConfig()
                .connectorProvider(HttpClientConnector::new), HttpMethod.GET, "/pingWithSleep");
    }

    @Test
    @Timeout(60)
    void testConcurrentHttp1JavaHttpClient() throws Exception {
        testConcurrent(new ClientConfig()
                .connectorProvider((jaxRsClient, configuration) -> getHttpClientConnector(jaxRsClient, HttpClient.Version.HTTP_1_1)));
    }

    private HttpClientConnector getHttpClientConnector(Client jaxRsClient, HttpClient.Version version) {
        return new HttpClientConnector(HttpClient.newBuilder().sslContext(jaxRsClient.getSslContext()).version(version).build());
    }


    private void testConcurrent(ClientConfig clientConfig) throws Exception {
        testConcurrent(clientConfig, "HEAD", PING);
    }

    private void testConcurrent(ClientConfig clientConfig, String method, String path) throws Exception {
        int port = PORT;
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        final KeyStore truststore = trustStore();
        try (AutoCloseable ignored = jerseyServer(
                port,
                tlsSecurityConfiguration,
                DummyRestService.class)) {
            final int nThreads = 4;
            final int iterations = 10_000;
            // Warmup
            Client client = ClientBuilder.newBuilder()
                    .trustStore(truststore)
                    .withConfig(clientConfig)
                    .build();
            final var webTarget = client.target("https://localhost:" + port).path(path);
            webTarget.request().method(method).close();

            AtomicInteger counter = new AtomicInteger();
            final Runnable runnable = () -> {
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    try (Response response = webTarget.request().method(method)) {

                        response.readEntity(InputStream.class).readAllBytes();
                        response.getStatus();
                        counter.incrementAndGet();
                        int reportEveryRequests = 1_000;
                        if (i % reportEveryRequests == 0) {
                            System.out.println(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) * 1.0 / reportEveryRequests);
                            start = System.nanoTime();
                        }
                    } catch (ProcessingException | IOException e) {
                        if (e.getMessage().contains("GOAWAY")
                                || e.getMessage().contains("Broken pipe") //  The HTTP sending process failed with error, Broken pipe
                                || e.getMessage().contains("EOF reached while reading")
                                || e.getMessage().contains(" cancelled")) {//  The HTTP sending process failed with error, Stream 673 cancelled
                            i--;
                        } else {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            };
            List<Throwable> thrown = new ArrayList<>();
            Thread.setDefaultUncaughtExceptionHandler((t1, e) -> {
                thrown.add(e);
                e.printStackTrace();
            });
            final Set<Thread> threads = IntStream
                    .range(0, nThreads)
                    .mapToObj(i -> runnable)
                    .map(Thread::new)
                    .collect(Collectors.toSet());

            threads.forEach(Thread::start);


            for (Thread thread : threads) {
                thread.join();
            }

            assertThat(thrown, Matchers.empty());
            assertEquals((long) nThreads * iterations, counter.get());

        }
    }

    @Test
    void shouldWorkInLoop() throws Exception {
        int port = PORT;
        JettyServer.TlsSecurityConfiguration tlsSecurityConfiguration = tlsConfig();
        for (int i = 0; i < 100; i++) {
            try (
                    @SuppressWarnings("unused") WeldContainer container = new Weld().initialize();
                    AutoCloseable ignored = jerseyServer(port, tlsSecurityConfiguration, DummyRestService.class);
                    final var head = getClient(port).path(PING).request().head()
            ) {
                assertEquals(204, head.getStatus());
            }
        }
    }


}