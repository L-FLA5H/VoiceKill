# Shriek 接口速查（反编译结论）

> 本文保留 1.0.14 的接口交接资料。1.0.15-dev.1 不再借用 `speechRecognizer` 或调用 `resetListener()`；
> 改为独立管理 Vosk Model/Recognizer，仅复用模型准备与文本通道。Shriek 停止相关字段已核对 1.1.22+fabric 字节码。

> 音识别这一层完全依赖 [Shriek](https://modrinth.com/mod/shriek)（MIT，作者 Pryzmm）。
> 它没有文档，接口是当初拆 `shriek.jar` 字节码摸出来的。**这份文档就是那些结论**，
> 你不用再拆一遍；想重新验证，用 `tools/inspect-shriek.ps1`。

## 0. 先把 jar 找出来

| 位置 | 路径 |
| --- | --- |
| Gradle 缓存（开发环境） | `~/.gradle/caches/modules-2/files-2.1/maven.modrinth/shriek/1.1.22+fabric/*/shriek-1.1.22+fabric.jar` |
| 游戏里 | `<游戏目录>/mods/shriek-fabric-1.1.22.jar` |
| 官方 | https://modrinth.com/mod/shriek → Versions |

```bash
# 一条命令看某个类的签名（jdk 21+ 自带 javap）
javap -p -classpath shriek.jar com.pryzmm.client.event.EventHandler
```

---

## 1. `com.pryzmm.api.ShriekApi` —— 唯一的「官方」入口

```java
public class ShriekApi {
  public static void registerServerPlayerSpeechListener(Consumer<ServerPlayerTalkEvent>);
  public static void registerClientSpeechListener(Consumer<ClientTalkEvent>);
  public static void setPrintToChat(boolean);
  public static void setPrintToConsole(boolean);
  public static void fireServerPlayerTalkEvent(ServerPlayerTalkEvent);
  public static void fireClientTalkEvent(ClientTalkEvent);
}
```

**只有一个能力：听**。没有「发文本」的公开方法 —— 发包得走下面的 `ShriekPackets`。

`ServerPlayerTalkEvent` 是接口，两个方法：

```java
String getText();                 // 识别出的文本
net.minecraft.server.level.ServerPlayer getPlayer();   // 谁说的（混淆名 class_3222）
```

本项目：`SpeechHandler.register()` 里注册监听。

---

## 2. `com.pryzmm.speech.SpeechRecognizer` —— 识别器

```java
public class SpeechRecognizer {
  public org.vosk.Recognizer recognizer;        // ★ public 字段，直接可拿
  public SpeechRecognizer(org.vosk.Model, int);
  public String getStringMsg(byte[]);           // 喂一段 PCM，返回文本（没说完返回空串）
  public static String repairEncoding(String, String, String);
}
```

`getStringMsg` 的实际行为（字节码）：

```java
if (recognizer.acceptWaveForm(bytes, bytes.length)) {
    String json = recognizer.getResult();          // {"text":"...","alternatives":[...]}
    return JsonParser.parseString(json)...get("text").getAsString();
}
return "";                                         // 没检测到句尾
```

**结论**：它只在「端点（说完一句）」时返回，返回的是**整句**，不是增量。
也正因为如此，`秒切` 被短停顿切开时会变成两次返回 —— 这是「词被拆成两截」的根因。

---

## 3. `com.pryzmm.client.event.EventHandler` —— 内部实现（靠反射用）

```java
public class EventHandler {
  private static SpeechRecognizer speechRecognizer;   // 反射读
  private static Thread listenThread;                 // 反射读（判断麦克风在不在跑）
  private static Timer retryTimer;                    // 反射取消（否则每 10 秒抢设备）
  private static boolean voskInitialized;

  public static String getOrCreatePath(String model); // 准备模型目录（★ 同步下载！）
  public static void loadVoskModel();                 // 用默认模型
  public static void loadVoskModel(String model);     // 指定模型（★ 会重建麦克风）
  public static void resetListener();                 // 只停，不重启
  public static void setModelPath(java.nio.file.Path);
  public static void register();
}
```

### 三条必须知道的行为

| 行为 | 后果 |
| --- | --- |
| `getOrCreatePath()` **同步下载**模型（40MB+） | 千万别放在渲染线程（`ClientTickEvents`）。1.0.0 就是这么把游戏冻了 8 分钟 |
| `resetListener()` **只 interrupt + 置空，不等线程结束，也不会重连** | 之后 `listenThread` 永远是 null；再调 `loadVoskModel()` 会和没退出的旧线程抢同一个识别器 → `ASSERTION_FAILED` → JVM 死 |
| `retryTimer` 每约 10 秒尝试重新初始化麦克风 | 自己接管录音后必须反射 `cancel()` 掉它，否则它回来抢设备、刷 `Error in listenThreadTask` |

---

## 4. `com.pryzmm.networking.ShriekPackets` + `PlayerSpeakPacket` —— 发文本

```java
public class ShriekPackets {
  public static void sendToServer(net.minecraft.network.protocol.common.custom.CustomPacketPayload);
}

public class PlayerSpeakPacket implements CustomPacketPayload {
  public PlayerSpeakPacket(String text);     // 服务端会转成 ServerPlayerTalkEvent
  public static final ... PLAYER_SPEAK_PACKET_TYPE;
  public static final ... STREAM_CODEC;
  public static void handleServerSide(PlayerSpeakPacket, NetworkManager.PacketContext);
}
```

**这行是本项目的命脉**：`MicCapture` 自己采音频、自己识别，然后用它把文本送上服务端：

```java
ShriekPackets.sendToServer(new PlayerSpeakPacket(text));
```

于是服务端看到的仍然是正常的 `ServerPlayerTalkEvent` —— 判定逻辑完全不用改。

> 协议扩展：本项目用不可见字符 `\u0001` 把「多候选」串在同一个字符串里。
> 服务端第 1 段进词频统计，所有段参与匹配。见 `SpeechHandler.handleText`。

---

## 5. `com.pryzmm.ShriekConstants` —— 默认值

```java
public static Path   acousticModelPath = Path.of("shriek/vosk");     // ★ 模型根目录
public static int    cacheSize         = 4096;                       // 每次读的字节数
public static String modelType         = "vosk-model-small-en-us-0.15";
public static int    sampleRate        = 16000;                      // ★ 必须 16k mono 16bit
public static boolean autoSend         = true;
public static String prefix            = "?Speech Input?";
public static boolean encoding_repair  = false;                      // 见下方 TODO
public static String srcEncoding       = "file.encoding";            // 取系统属性的键名
public static String dstEncoding       = "file.encoding";
```

**模型路径**是 `shriek/vosk`（相对游戏目录），不是游戏根目录 —— 放错位置它会重新下一遍。
本项目 `MicCapture` 用的音频格式必须和 `sampleRate` 一致：

```java
new AudioFormat(16000f, 16, 1, true, false)   // 16kHz / 16bit / 单声道 / signed / little-endian
```

### 未查完的线索：`encoding_repair`

联机时有人的中文会被解码成乱码（`鎴� 杩樹互涓` = 「我还以为」的 UTF-8 字节被当 GBK 读）。
服务端救不回来（字节已经丢了，出现 `U+FFFD`），只能在客户端修。
Shriek 自带 `encoding_repair / srcEncoding / dstEncoding` 三个开关，默认全关。
**没验证过**：这个修复是在 `new String(bytes)` 之后做的，如果已经是 `U+FFFD` 就无效。
想试的人在客户端把 `encoding_repair` 打开，看日志里 `repairEncoding` 有没有作用。
（用户侧现成的解法是给 JVM 加 `-Dfile.encoding=UTF-8`，已写进联机说明。）

---

## 6. `org.vosk.Recognizer` —— Vosk 本体（Shriek 内嵌，不单独依赖）

```java
public class Recognizer {
  public Recognizer(Model model, float sampleRate);
  public void setMaxAlternatives(int n);      // ★ 多候选
  public void setWords(boolean);              // 词级时间戳/置信度
  public boolean acceptWaveForm(byte[] b, int len);   // true = 检测到句尾
  public String getResult();                  // {"text":"...","alternatives":[{"text":...},...]}
  public String getPartialResult();
  public void setGrammar(String json);        // 语法约束（本项目未使用）
  public void reset();
  public void close();
}
```

**注意**：`org.vosk.*` 类**在 shriek.jar 内部**，没有独立 Maven 坐标。
本项目对它的调用全部走反射（`MicCapture.prepareNBest`），编译期不依赖，
拿不到就退回 `getStringMsg` 单结果 —— 这样 Shriek 升级/换实现也不会编译失败。

---

## 7. 重新提取

```powershell
# 在 PowerShell 里（把路径换成你的 jar）
.\tools\inspect-shriek.ps1 -Jar "D:\...\mods\shriek-fabric-1.1.22.jar"
```

脚本会依次 `javap -p` 上面这些类，输出到 `docs/shriek-javap.txt`，方便 diff 新旧版本。
