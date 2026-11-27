package com.limelight.nvstream.http;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NvHTTPCancellationTest {
    private NvHTTP http() throws IOException {
        return new NvHTTP(new ComputerDetails.AddressTuple("127.0.0.1", 47989), 47984,
                "test-client", null, mock(LimelightCryptoProvider.class));
    }

    private OkHttpClient intercept(NvHTTP http, String field, Interceptor interceptor) {
        OkHttpClient original = ReflectionHelpers.getField(http, field);
        OkHttpClient client = original.newBuilder().addInterceptor(interceptor).build();
        ReflectionHelpers.setField(http, field, client);
        return client;
    }

    @Test public void cancelledAttemptRejectsLaterPairingCallsAndCertificateChanges() throws Exception {
        NvHTTP http = http();
        AtomicInteger reachedServer = new AtomicInteger();
        Interceptor server = chain -> {
            reachedServer.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create("<root><paired>1</paired></root>", MediaType.get("text/xml")))
                    .build();
        };
        intercept(http, "httpClientLongConnectTimeout", server);
        OkHttpClient unlimited = intercept(http, "httpClientLongConnectNoReadTimeout", server);
        assertEquals(0, unlimited.readTimeoutMillis());
        http.executePairingCommand("clientchallenge=first", true);
        X509Certificate original = mock(X509Certificate.class);
        http.setServerCert(original);
        // This call exists before cancellation but has not entered the dispatcher yet.
        Call prepared = unlimited.newCall(new Request.Builder().url("http://127.0.0.1/pair").build());
        http.cancelPendingRequests();
        assertThrows(IOException.class, prepared::execute);
        assertThrows(IOException.class, () -> http.executePairingCommand("phrase=getservercert", false));
        assertThrows(IOException.class, () -> http.executePairingCommand("clientchallenge=next", true));
        assertThrows(IOException.class, http::executePairingChallenge);
        assertThrows(IOException.class, () -> http.setServerCert(mock(X509Certificate.class)));
        assertSame(original, ReflectionHelpers.getField(http, "serverCert"));
        assertEquals(1, reachedServer.get());
    }

    @Test public void cancellationAlsoCancelsAnAlreadyRegisteredUnlimitedReadCall() throws Exception {
        NvHTTP http = http();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        intercept(http, "httpClientLongConnectNoReadTimeout", chain -> {
            entered.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            assertTrue(chain.call().isCanceled());
            throw new IOException("Cancelled in-flight request");
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = worker.submit(() -> {
                assertThrows(IOException.class, () -> http.executePairingCommand("phrase=getservercert", false));
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            http.cancelPendingRequests();
            finish.countDown();
            result.get(5, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
            worker.shutdownNow();
        }
    }
}
