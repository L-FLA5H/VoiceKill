package com.example.voicekill.client;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** 固定版本、固定哈希的离线模型缓存；不上传声音，不覆盖已有的损坏/自定义模型。 */
public final class SpeechModels {
    public record Asset(String name, String url, long size, String sha256) {}
    public record Manifest(String id, String source, String revision, String license, List<Asset> files) {}
    public enum Stage { CHECKING, DOWNLOADING, VERIFYING, READY, FAILED, CANCELLED }
    /** Immutable per-directory snapshots; only the downloader writes, UI reads without blocking. */
    public record Progress(String id, Stage stage, long completedBytes, long totalBytes,
                           String file, int fileIndex, int fileCount, String error) {
        public double fraction() {return totalBytes<=0?0:Math.max(0,Math.min(1,(double)completedBytes/totalBytes));}
    }
    private static final java.util.concurrent.ConcurrentMap<Path,Progress> PROGRESS=new java.util.concurrent.ConcurrentHashMap<>();
    public static Progress progress(Path directory) {return PROGRESS.get(directory.toAbsolutePath().normalize());}
    private static void publish(Path directory,Manifest manifest,Stage stage,long completed,long total,String file,int index,String error) {
        PROGRESS.put(directory.toAbsolutePath().normalize(),new Progress(manifest.id(),stage,completed,total,file,index,manifest.files().size(),error));
    }
    private SpeechModels() {}
    private static final java.util.concurrent.ConcurrentMap<Path, java.util.concurrent.locks.ReentrantLock> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要一个模型缓存目录参数");
        System.out.println("ASR_MODEL_READY=" + prepare(Path.of(args[0]), true).toAbsolutePath());
    }

    public static Manifest manifest() throws IOException {
        return manifest("zipformer-bilingual-small-int8");
    }
    public static Manifest manifest(String id) throws IOException {
        if(id==null||!id.matches("[a-z][a-z0-9-]{0,63}"))throw new IOException("无效模型标识");
        try (InputStream input = SpeechModels.class.getResourceAsStream("/voicekill/asr/"+id+".json")) {
            if (input == null) throw new IOException("缺少双语模型清单");
            return new Gson().fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), Manifest.class);
        }
    }

    public static Path prepare(Path directory, boolean download) throws Exception {
        return prepare(directory,download,"zipformer-bilingual-small-int8");
    }
    public static Path prepare(Path directory, boolean download,String id) throws Exception {
        return prepare(directory,download,manifest(id));
    }
    // Same production path with an explicit pinned manifest for local HTTP integration tests.
    static Path prepare(Path directory,boolean download,Manifest manifest) throws Exception {
        var lock=LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize(),key->new java.util.concurrent.locks.ReentrantLock());
        lock.lockInterruptibly();
        long total=manifest.files().stream().mapToLong(Asset::size).sum();
        try {
            publish(directory,manifest,Stage.CHECKING,0,total,"",0,"");
            return prepareLocked(directory,download,manifest,total);
        } catch(Exception failure) {
            Progress last=progress(directory);
            boolean cancelled=Thread.currentThread().isInterrupted();
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof InterruptedException)cancelled=true;
            publish(directory,manifest,cancelled?Stage.CANCELLED:Stage.FAILED,last.completedBytes(),total,last.file(),last.fileIndex(),CaptureSession.rootCause(failure));
            if(cancelled)Thread.currentThread().interrupt();
            throw failure;
        } finally {lock.unlock();}
    }
    private static Path prepareLocked(Path directory,boolean download,Manifest manifest,long total) throws Exception {
        Files.createDirectories(directory);
        long completed=0;int index=0;
        try(HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build()) {
            for(Asset asset:manifest.files()) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("模型准备已取消");
                index++;Path target=directory.resolve(asset.name());
                publish(directory,manifest,Stage.CHECKING,completed,total,asset.name(),index,"");
                if(Files.exists(target)) {
                    validate(target,asset);completed+=asset.size();
                    publish(directory,manifest,Stage.CHECKING,completed,total,asset.name(),index,"");
                    continue;
                }
                if(!download)throw new IOException("缺少模型文件："+target+"；请开启 autoModelDownload 或手动安装");
                Path temporary=Files.createTempFile(directory,"voicekill-download-",".part");
                try {
                    publish(directory,manifest,Stage.DOWNLOADING,completed,total,asset.name(),index,"");
                    HttpRequest request=HttpRequest.newBuilder(URI.create(asset.url())).timeout(Duration.ofMinutes(5)).GET().build();
                    var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
                    try(InputStream input=response.body()) {
                        if(response.statusCode()!=200)throw new IOException("模型下载 HTTP "+response.statusCode());
                        try(var output=Files.newOutputStream(temporary)) {
                            byte[] buffer=new byte[65536];long received=0,lastUpdate=0;int count;
                            while((count=input.read(buffer))!=-1) {
                                if(Thread.currentThread().isInterrupted())throw new InterruptedException("模型下载已取消");
                                received+=count;
                                if(received>asset.size())throw new IOException("模型下载超出清单大小："+asset.name());
                                output.write(buffer,0,count);
                                long now=System.nanoTime();
                                if(now-lastUpdate>=100_000_000L) {
                                    publish(directory,manifest,Stage.DOWNLOADING,completed+received,total,asset.name(),index,"");lastUpdate=now;
                                }
                            }
                            publish(directory,manifest,Stage.VERIFYING,completed+received,total,asset.name(),index,"");
                        }
                    }
                    validate(temporary,asset);
                    Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
                    completed+=asset.size();
                } finally {Files.deleteIfExists(temporary);}
            }
        }
        publish(directory,manifest,Stage.READY,completed,total,"",manifest.files().size(),"");
        return directory;
    }

    public static void validate(Path file, Asset asset) throws Exception {
        if (Files.size(file) != asset.size()) throw new IOException("模型大小校验失败：" + file + "；请移走该文件后重新下载");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(asset.sha256())) {
            throw new IOException("模型 SHA256 校验失败：" + file + "；原文件未覆盖");
        }
    }
}
