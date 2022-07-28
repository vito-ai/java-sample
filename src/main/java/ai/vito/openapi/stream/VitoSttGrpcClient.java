package ai.vito.openapi.stream;

import ai.vito.openapi.auth.Auth;
import ai.vito.openapi.v1.*;

import com.google.protobuf.ByteString;

import io.grpc.*;
import io.grpc.stub.StreamObserver;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VitoSttGrpcClient {
    private static final Logger logger = Logger.getLogger(VitoSttGrpcClient.class.getName());

    private final OnlineDecoderGrpc.OnlineDecoderStub asyncStub;
    private final StreamObserver<DecoderRequest> decoder;
    private final CountDownLatch finishLatch;

    public VitoSttGrpcClient(Channel channel, final String token, final StreamObserver<DecoderResponse> observer) {
        finishLatch = new CountDownLatch(1);
        asyncStub = OnlineDecoderGrpc.newStub(channel)
                .withCallCredentials(new CallCredentials() {
                    @Override
                    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
                        final Metadata metadata = new Metadata();
                        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
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
    
    public static void main(String[] args) throws Exception {

        ManagedChannel channel = ManagedChannelBuilder.forTarget("grpc-openapi.vito.ai:443")
                .useTransportSecurity()
                .build();
        
        String token = Auth.getAccessToken();
        
        VitoSttGrpcClient client = new VitoSttGrpcClient(channel, token, new StreamObserver<DecoderResponse>() {
            @Override
            public void onNext(DecoderResponse value) {
                StreamingRecognitionResult result = value.getResults(0);
                SpeechRecognitionAlternative best = result.getAlternatives(0);
                if (result.getIsFinal()) {
                    System.out.printf("final:%6d,%6d: %s\n", result.getStartAt(), result.getDuration(), best.getText());
                } else {
                    System.out.printf(best.getText() + "\n") ;
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
        File file = new File("sample.wav");
        AudioInputStream in = AudioSystem.getAudioInputStream(file);
        DecoderConfig config = DecoderConfig.newBuilder().
                setSampleRate(8000).
                setEncoding(DecoderConfig.AudioEncoding.LINEAR16).
                setUseItn(true).
                setUseDisfluencyFilter(true).
                setUseProfanityFilter(true).build();

        client.setDecoderConfig(config);
        byte[] buffer = new byte[1024];
        int readed = 0;
        // Try to read numBytes bytes from the file.
        while ((readed = in.read(buffer)) != -1) {
            client.send(buffer, readed);
        }
        client.closeSend();
        client.await();
    }
}
