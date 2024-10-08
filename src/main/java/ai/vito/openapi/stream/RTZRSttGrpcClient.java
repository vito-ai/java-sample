/* 해당 예제는 아래 파일 포맷을 지원합니다
WAV, AU, AIFF
https://docs.oracle.com/javase/8/docs/technotes/guides/sound/index.html
*/

package ai.vito.openapi.stream;

import ai.vito.openapi.auth.Auth;
import ai.vito.openapi.v1.*;

import com.google.protobuf.ByteString;

import io.grpc.*;
import io.grpc.stub.StreamObserver;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.*;

/*
본 예제에서는 스트리밍 입력을 음성파일을 읽어서 시뮬레이션 합니다.
실제사용시에는 마이크 입력 등의 실시간 음성 스트림이 들어와야합니다.
*/
final class FileStreamer {
    private AudioInputStream audio8KStream;
    private int SAMPLE_RATE = 8000;
    private int BITS_PER_SAMPLE = 16;

    public FileStreamer(String filePath) throws IOException, UnsupportedAudioFileException {
        File file = new File(filePath);
        try {
            AudioInputStream originalAudioStream = AudioSystem.getAudioInputStream(file);
            AudioFormat originalFormat = originalAudioStream.getFormat();
            AudioFormat newFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SAMPLE_RATE,
                    BITS_PER_SAMPLE,
                    1,
                    1 * (BITS_PER_SAMPLE / 8),
                    SAMPLE_RATE,
                    originalFormat.isBigEndian());

            this.audio8KStream = AudioSystem.getAudioInputStream(newFormat, originalAudioStream);
        } catch (IOException | UnsupportedAudioFileException e) {
            throw e;
        }
    }

    public int read(byte[] b) throws IOException, InterruptedException {
        int maxSize = 1024 * 1024;
        int byteSize = Math.min(b.length, maxSize);
        try {
            Thread.sleep(byteSize / ((SAMPLE_RATE * (BITS_PER_SAMPLE / 8)) / 1000));
        } catch (InterruptedException e) {
            throw e;
        }
        return this.audio8KStream.read(b, 0, byteSize);
    }

    public void close() throws IOException {
        this.audio8KStream.close();
    }
}

public class RTZRSttGrpcClient {
    private static final Logger logger = Logger.getLogger(RTZRSttGrpcClient.class.getName());

    private final OnlineDecoderGrpc.OnlineDecoderStub asyncStub;
    private final StreamObserver<DecoderRequest> decoder;
    private final CountDownLatch finishLatch;

    public RTZRSttGrpcClient(Channel channel, final String token, final StreamObserver<DecoderResponse> observer) {
        finishLatch = new CountDownLatch(1);
        asyncStub = OnlineDecoderGrpc.newStub(channel)
                .withCallCredentials(new CallCredentials() {
                    @Override
                    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
                            MetadataApplier applier) {
                        final Metadata metadata = new Metadata();
                        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                                "Bearer " + token);
                        applier.apply(metadata);
                    }

                    @Override
                    public void thisUsesUnstableApi() {

                    }
                });
        decoder = asyncStub.decode(new StreamObserver<DecoderResponse>() {
            @Override
            public void onNext(DecoderResponse value) {
                observer.onNext(value);
            }

            @Override
            public void onError(Throwable t) {
                observer.onError(t);
                finishLatch.countDown();
            }

            @Override
            public void onCompleted() {
                observer.onCompleted();
                finishLatch.countDown();
            }
        });
    }

    public void await(long timeout, TimeUnit unit) throws InterruptedException {
        finishLatch.await(timeout, unit);
    }

    public void await() throws InterruptedException {
        finishLatch.await();
    }

    public void setDecoderConfig(DecoderConfig config) {
        decoder.onNext(DecoderRequest.newBuilder().setStreamingConfig(config).build());
    }

    public void send(byte[] buff, int size) {
        decoder.onNext(DecoderRequest.newBuilder().setAudioContent(ByteString.copyFrom(buff, 0, size)).build());
    }

    public void closeSend() {
        decoder.onCompleted();
    }

    private static void log(Level level, String msg, Object... args) {
        logger.log(level, msg, args);
    }

    private static void log(Level level, String msg, Throwable t) {
        logger.log(level, msg, t);
    }

    public static void main(String[] args) throws Exception {

        ManagedChannel channel = ManagedChannelBuilder.forTarget("grpc-openapi.vito.ai:443")
                .useTransportSecurity()
                .build();

        String token = Auth.getAccessToken();

        RTZRSttGrpcClient client = new RTZRSttGrpcClient(channel, token, new StreamObserver<DecoderResponse>() {
            @Override
            public void onNext(DecoderResponse value) {
                StreamingRecognitionResult result = value.getResults(0);
                SpeechRecognitionAlternative best = result.getAlternatives(0);
                if (result.getIsFinal()) {
                    System.out.printf("final:%6d,%6d: %s\n", result.getStartAt(), result.getDuration(), best.getText());
                } else {
                    System.out.printf(best.getText() + "\n");
                }
            }

            @Override
            public void onError(Throwable t) {
                log(Level.WARNING, "on error", t);
            }

            @Override
            public void onCompleted() {
                log(Level.INFO, "Complete");
            }
        });
        FileStreamer fileStreamer = new FileStreamer(
                "sample.wav");

        DecoderConfig config = DecoderConfig.newBuilder().setSampleRate(8000)
                .setEncoding(DecoderConfig.AudioEncoding.LINEAR16).setUseItn(true).setUseDisfluencyFilter(true)
                .setUseProfanityFilter(true).build();

        client.setDecoderConfig(config);
        byte[] buffer = new byte[1024];
        int readBytes = 0;
        // Try to read numBytes bytes from the file.
        while ((readBytes = fileStreamer.read(buffer)) != -1) {
            client.send(buffer, readBytes);
        }
        fileStreamer.close();
        client.closeSend();
        client.await();
    }
}
