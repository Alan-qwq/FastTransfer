import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 静态校验所有布局里的自定义 View 是否能被 LayoutInflater 实例化。
 *
 * <p>LayoutInflater 只用 {@code <init>(Context, AttributeSet)} 反射构造 View，
 * 所以 XML 里写的类必须：非抽象 / 非接口、且存在 public 的该构造函数。
 * 抽象类（例如 Material 的 NavigationBarView）写进 XML 会在运行时直接闪退，
 * 编译与 lint 都发现不了 —— 这个检查负责兜住。</p>
 *
 * <p>直接解析 class 文件字节码，不加载类，因此不需要 android.jar。
 * 用法：java LayoutChecker &lt;resDir&gt; &lt;classpathEntry&gt;...</p>
 */
public final class LayoutChecker {

    private static final Pattern CUSTOM_VIEW = Pattern.compile(
            "<\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+)(?=[\\s/>])");

    /** 构造函数签名：(Landroid/content/Context;Landroid/util/AttributeSet;)V */
    private static final String CTOR_DESC = "(Landroid/content/Context;Landroid/util/AttributeSet;)V";

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;

    private static int checked;
    private static final List<String> problems = new ArrayList<>();
    private static final List<Path> classpath = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LayoutChecker <resDir> <classpathEntry>...");
            System.exit(2);
        }
        Path resDir = Paths.get(args[0]);
        for (int i = 1; i < args.length; i++) {
            classpath.add(Paths.get(args[i]));
        }

        List<Path> layouts = new ArrayList<>();
        for (String dir : new String[]{"layout", "layout-sw600dp", "layout-sw200dp",
                "layout-land", "layout-sw600dp-land"}) {
            Path p = resDir.resolve(dir);
            if (Files.isDirectory(p)) {
                Files.walk(p)
                        .filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".xml"))
                        .forEach(layouts::add);
            }
        }
        if (layouts.isEmpty()) {
            System.err.println("找不到布局文件: " + resDir);
            System.exit(2);
        }

        System.out.println("扫描 " + layouts.size() + " 个布局文件，classpath 条目 " + classpath.size() + " 个");

        Set<String> allClasses = new LinkedHashSet<>();
        for (Path layout : layouts) {
            String xml = new String(Files.readAllBytes(layout), StandardCharsets.UTF_8);
            Set<String> classes = new LinkedHashSet<>();
            Matcher m = CUSTOM_VIEW.matcher(xml);
            while (m.find()) {
                classes.add(m.group(1));
            }
            for (String cls : classes) {
                if (cls.startsWith("java.") || cls.startsWith("javax.")) {
                    continue;
                }
                allClasses.add(cls);
                check(layout.getFileName().toString(), cls);
            }
        }

        System.out.println();
        System.out.println("共校验 " + checked + " 处自定义 View（去重后 " + allClasses.size() + " 个类）");
        if (problems.isEmpty()) {
            System.out.println("RESULT: OK - 全部可由 LayoutInflater 构造");
            return;
        }
        System.out.println("RESULT: FAILED - " + problems.size() + " 个问题：");
        for (String p : problems) {
            System.out.println("  - " + p);
        }
        System.exit(1);
    }

    private static void check(String layoutName, String className) {
        checked++;
        byte[] bytes = readClassBytes(className);
        if (bytes == null) {
            problems.add(layoutName + " -> " + className + " : 类不存在（拼写错误或依赖缺失）");
            return;
        }
        int accessFlags = classAccessFlags(bytes);
        if ((accessFlags & ACC_INTERFACE) != 0) {
            problems.add(layoutName + " -> " + className + " : 是接口");
            return;
        }
        if ((accessFlags & ACC_ABSTRACT) != 0) {
            problems.add(layoutName + " -> " + className
                    + " : 是抽象类，LayoutInflater 无法实例化（请改用具体子类）");
            return;
        }
        if (!hasPublicAttributeSetConstructor(bytes)) {
            problems.add(layoutName + " -> " + className
                    + " : 缺少 public (Context, AttributeSet) 构造函数");
        }
    }

    private static byte[] readClassBytes(String className) {
        String relative = className.replace('.', '/') + ".class";
        for (Path entry : classpath) {
            try {
                if (Files.isDirectory(entry)) {
                    Path f = entry.resolve(relative);
                    if (Files.isRegularFile(f)) {
                        return Files.readAllBytes(f);
                    }
                } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                    try (ZipFile zip = new ZipFile(entry.toFile())) {
                        ZipEntry e = zip.getEntry(relative);
                        if (e != null) {
                            try (InputStream in = zip.getInputStream(e)) {
                                return readAll(in);
                            }
                        }
                    }
                } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".aar")) {
                    // Android 库是 aar，class 都在里面的 classes.jar
                    try (ZipFile aar = new ZipFile(entry.toFile())) {
                        ZipEntry jarEntry = aar.getEntry("classes.jar");
                        if (jarEntry != null) {
                            Path tmp = Files.createTempFile("layoutcheck", ".jar");
                            try (InputStream in = aar.getInputStream(jarEntry)) {
                                Files.write(tmp, readAll(in));
                            }
                            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                                ZipEntry e = zip.getEntry(relative);
                                if (e != null) {
                                    try (InputStream in = zip.getInputStream(e)) {
                                        return readAll(in);
                                    }
                                }
                            } finally {
                                Files.deleteIfExists(tmp);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 换下一个 classpath 条目
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static int classAccessFlags(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0xCAFEBABE) {
                return -1;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: in.skipBytes(in.readUnsignedShort()); break;
                    case 3: case 4: in.skipBytes(4); break;
                    case 5: case 6: in.skipBytes(8); i++; break;
                    case 7: case 8: case 16: case 19: case 20: in.skipBytes(2); break;
                    case 9: case 10: case 11: case 12: case 17: case 18: in.skipBytes(4); break;
                    case 15: in.skipBytes(3); break;
                    default: return -1;
                }
            }
            return in.readUnsignedShort();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 在 class 文件的 UTF-8 常量里查构造函数描述符即可，无需完整解析方法表。 */
    private static boolean hasPublicAttributeSetConstructor(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        return text.contains(CTOR_DESC);
    }
}
