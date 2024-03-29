package ai.vito.openapi.stream;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CountDownLatch;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import ai.vito.openapi.auth.Auth;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class VitoSttWebSocketClient {

    public static void main(String[] args) throws Exception {
        Logger logger = Logger.getLogger(VitoSttWebSocketClient.class.getName());
        OkHttpClient client = new OkHttpClient();

        String token = Auth.getAccessToken();

        HttpUrl.Builder httpBuilder = HttpUrl.get("https://openapi.vito.ai/v1/transcribe:streaming").newBuilder();
        httpBuilder.addQueryParameter("sample_rate", "8000");
        httpBuilder.addQueryParameter("encoding", "LINEAR16");
        httpBuilder.addQueryParameter("use_itn", "true");
        httpBuilder.addQueryParameter("use_disfluency_filter", "true");
        httpBuilder.addQueryParameter("use_profanity_filter", "true");

        String url = httpBuilder.toString().replace("https://", "wss://");

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        VitoWebSocketListener webSocketListener = new VitoWebSocketListener();
        WebSocket vitoWebSocket = client.newWebSocket(request, webSocketListener);

        AudioInputStream in = null;
        try {
            File file = new File("sample.wav");
            in = AudioSystem.getAudioInputStream(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        byte[] buffer = new byte[1024];
        int readBytes;
        while ((readBytes = in.read(buffer)) != -1) {
            boolean sent = vitoWebSocket.send(ByteString.of(buffer, 0, readBytes));
            if (!sent) {
                logger.log(Level.WARNING, "Send buffer is full. Cannot complete request. Increase sleep interval.");
                System.exit(1);
            }
            Thread.sleep(0, 50);
        }
        in.close();
        vitoWebSocket.send("EOS");

        webSocketListener.waitClose();
        client.dispatcher().executorService().shutdown();
    }
}

class VitoWebSocketListener extends WebSocketListener {
    private static final Logger logger = Logger.getLogger(VitoSttWebSocketClient.class.getName());
    private static final int NORMAL_CLOSURE_STATUS = 1000;
    private CountDownLatch latch = null;

    private static void log(Level level, String msg, Object... args) {
        logger.log(level, msg, args);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        log(Level.INFO, "Open " + response.message());
        latch = new CountDownLatch(1);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        System.out.println(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        System.out.println(bytes.hex());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null);
        log(Level.INFO, "Closing {0} {1}", code, reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null);
        log(Level.INFO, "Closed {0} {1}", code, reason);
        latch.countDown();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        t.printStackTrace();
        latch.countDown();
    }

    public void waitClose() throws InterruptedException {
        log(Level.INFO, "Wait for finish");
        latch.await();
    }
}