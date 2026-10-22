package com.example.voicekill.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ModelDownloadProgressTest {
    @TempDir Path directory;
    private HttpServer server;
    @BeforeEach void startServer() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
    }
    @AfterEach void stopServer(){server.stop(0);}
    private SpeechModels.Asset asset(String name,String route,byte[] bytes) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new SpeechModels.Asset(name,"http://127.0.0.1:"+server.getAddress().getPort()+route,bytes.length,hash);
    }
    private SpeechModels.Manifest manifest(SpeechModels.Asset... assets){return new SpeechModels.Manifest("test-model","loopback","test","test",List.of(assets));}
    private void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"download state did not arrive");
    }
    @Test void actualHttpReadsPublishPartialBytesAndIncludeVerifiedCache() throws Exception {
        byte[] cached=new byte[16384],remote=new byte[262144];Arrays.fill(remote,(byte)31);
        var local=asset("tokens.txt","/unused",cached);var downloaded=asset("model.onnx","/slow",remote);
        Files.write(directory.resolve(local.name()),cached);
        var release=new CountDownLatch(1);
        server.createContext("/slow",exchange->{
            try(exchange) {
                exchange.sendResponseHeaders(200,remote.length);
                var output=exchange.getResponseBody();output.write(remote,0,32768);output.flush();
                try {if(!release.await(5,TimeUnit.SECONDS))return;}catch(InterruptedException failure){Thread.currentThread().interrupt();return;}
                output.write(remote,32768,remote.length-32768);
            }
        });
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->SpeechModels.prepare(directory,true,manifest(local,downloaded)));
            try {
                await(()->{var p=SpeechModels.progress(directory);return p!=null&&p.stage()==SpeechModels.Stage.DOWNLOADING&&p.completedBytes()>cached.length;});
                var partial=SpeechModels.progress(directory);
                assertTrue(partial.completedBytes()<partial.totalBytes());assertEquals(cached.length+remote.length,partial.totalBytes());
                assertEquals(2,partial.fileIndex());assertEquals(2,partial.fileCount());assertEquals("model.onnx",partial.file());
                assertTrue(partial.fraction()>0&&partial.fraction()<1);
            } finally {release.countDown();}
            assertEquals(directory,future.get(5,TimeUnit.SECONDS));
        }
        var ready=SpeechModels.progress(directory);assertEquals(SpeechModels.Stage.READY,ready.stage());
        assertEquals(1.,ready.fraction());assertArrayEquals(remote,Files.readAllBytes(directory.resolve("model.onnx")));
        try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.toString().endsWith(".part")));}
    }
    @Test void fullDownloadWithWrongHashIsFailureAndRetryCanBecomeReady() throws Exception {
        byte[] valid=new byte[4096];Arrays.fill(valid,(byte)3);
        var body=new AtomicReference<>(new byte[4096]);var calls=new AtomicInteger();
        server.createContext("/hash",exchange->{try(exchange){calls.incrementAndGet();byte[] bytes=body.get();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}});
        var spec=manifest(asset("model.onnx","/hash",valid));
        assertThrows(Exception.class,()->SpeechModels.prepare(directory,true,spec));
        var failed=SpeechModels.progress(directory);assertEquals(SpeechModels.Stage.FAILED,failed.stage());
        assertEquals(1.,failed.fraction());assertTrue(failed.error().contains("SHA256"));
        assertFalse(Files.exists(directory.resolve("model.onnx")));
        try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.toString().endsWith(".part")));}
        body.set(valid);SpeechModels.prepare(directory,true,spec);
        assertEquals(SpeechModels.Stage.READY,SpeechModels.progress(directory).stage());assertEquals(2,calls.get());
    }
    @Test void httpFailureIsVisibleAndDoesNotPublishReady() throws Exception {
        server.createContext("/unavailable",exchange->{try(exchange){exchange.sendResponseHeaders(503,-1);}});
        assertThrows(Exception.class,()->SpeechModels.prepare(directory,true,manifest(asset("model.onnx","/unavailable",new byte[4096]))));
        var progress=SpeechModels.progress(directory);assertEquals(SpeechModels.Stage.FAILED,progress.stage());
        assertEquals(0,progress.completedBytes());assertTrue(progress.error().contains("503"));
    }
    @Test void offlineCacheReportsVerifiedReadyWithoutHttpAndDirectoriesStayIndependent() throws Exception {
        byte[] bytes=new byte[4096];var spec=manifest(asset("model.onnx","/must-not-request",bytes));
        Files.write(directory.resolve("model.onnx"),bytes);SpeechModels.prepare(directory,false,spec);
        Path missing=directory.resolve("other");assertThrows(Exception.class,()->SpeechModels.prepare(missing,false,spec));
        assertEquals(SpeechModels.Stage.READY,SpeechModels.progress(directory).stage());
        assertEquals(SpeechModels.Stage.FAILED,SpeechModels.progress(missing).stage());
        assertTrue(SpeechModels.progress(missing).error().contains("autoModelDownload"));
        assertEquals(SpeechModels.progress(directory),SpeechModels.progress(directory.resolve(".")));
    }
}
