package browser;

import arc.Core;
import arc.util.Log;
import org.cef.SystemBootstrap;

import java.io.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class NativeLoader {
    private static File nativesDir;
    private static boolean loaded = false;
    private static final String NATIVES_FOLDER = "jcef-natives";
    private static final String MARKER_FILE = ".natives-ok";

    public static void extractAndLoad() {
        if (loaded) return;

        try {
            String platform = detectPlatform();
            cleanupOldTempDirs();

            File modDir = new File(Core.settings.getDataDirectory().child("mods").file(), "browser-windows");
            File stableDir = new File(modDir, NATIVES_FOLDER);

            boolean needExtract = !new File(stableDir, MARKER_FILE).exists();

            if (needExtract) {
                deleteDir(stableDir);
                stableDir.mkdirs();

                String nativeJarName = findNativeJarName(platform);
                if (nativeJarName == null) {
                    throw new RuntimeException("jcef-natives JAR not found for " + platform + " inside the mod");
                }

                InputStream jarStream = NativeLoader.class.getClassLoader().getResourceAsStream(nativeJarName);
                if (jarStream == null) {
                    throw new RuntimeException("Cannot open stream to: " + nativeJarName);
                }

                boolean ok = extractFromJarStream(jarStream, stableDir);
                if (!ok) {
                    throw new RuntimeException("No .tar.gz found inside native JAR");
                }

                new File(stableDir, MARKER_FILE).createNewFile();
            }

            nativesDir = stableDir;

            File staleJawt = new File(stableDir, "jawt.dll");
            if (staleJawt.exists()) staleJawt.delete();

            preloadJawtFromJdk();
            installBootstrapLoader(stableDir);
            preloadNativeLibraries(stableDir);

            loaded = true;
        } catch (Exception e) {
            Log.err("[NativeLoader] FAILED: @", e.getMessage());
            throw new RuntimeException("Failed to load JCEF natives", e);
        }
    }

    public static File getNativesDir() {
        return nativesDir;
    }

    private static void cleanupOldTempDirs() {
        try {
            File tmpDir = new File(System.getProperty("java.io.tmpdir"));
            File[] oldDirs = tmpDir.listFiles((dir, name) -> name.startsWith("jcef-natives-"));
            if (oldDirs == null) return;
            int cleaned = 0;
            for (File old : oldDirs) {
                if (deleteDir(old)) cleaned++;
            }
            if (cleaned > 0) {
                Log.info("[BW] Cleaned @ old JCEF temp dirs", cleaned);
            }
        } catch (Exception e) {
            Log.warn("[NativeLoader] Cleanup warning: @", e.getMessage());
        }
    }

    private static boolean deleteDir(File dir) {
        if (dir == null || !dir.exists()) return false;
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        return dir.delete();
    }

    private static void preloadJawtFromJdk() {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("win")) return;

        File jawtFile = findLibOnSystem("jawt.dll");
        if (jawtFile == null) {
            Log.warn("[NativeLoader] jawt.dll not found on system");
            return;
        }

        File jdkBin = jawtFile.getParentFile();
        String[] chain = {"awt.dll", "jawt.dll"};
        for (String name : chain) {
            File f = new File(jdkBin, name);
            if (!f.exists()) continue;
            try {
                System.load(f.getAbsolutePath());
                Log.info("[NativeLoader] Preloaded @ from @", name, jdkBin.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                if (!e.getMessage().contains("already loaded")) {
                    Log.warn("[NativeLoader] Failed to load @: @", name, e.getMessage());
                }
            }
        }
    }

    private static File findLibOnSystem(String fileName) {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            for (String sub : new String[]{"bin", "lib"}) {
                File f = new File(javaHome, sub + File.separator + fileName);
                if (f.exists()) return f;
            }
            File parent = new File(javaHome).getParentFile();
            if (parent != null) {
                File f = new File(parent, "bin" + File.separator + fileName);
                if (f.exists()) return f;
            }
        }

        try {
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String p : pathEnv.split(File.pathSeparator)) {
                    File f = new File(p.trim(), fileName);
                    if (f.exists()) return f;
                }
            }
        } catch (Exception ignored) {}

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String[] roots = {
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\Java",
                "C:\\Program Files (x86)\\Java",
                "C:\\Program Files\\Microsoft\\jdk",
                "C:\\Program Files\\Zulu",
                "C:\\Program Files\\BellSoft",
                "C:\\Program Files\\Amazon Corretto",
                "C:\\Program Files\\AdoptOpenJDK"
            };
            for (String root : roots) {
                File rootDir = new File(root);
                if (!rootDir.isDirectory()) continue;
                File[] children = rootDir.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    File f = new File(child, "bin" + File.separator + fileName);
                    if (f.exists()) return f;
                }
            }
        }

        return null;
    }

    private static void installBootstrapLoader(File dir) {
        SystemBootstrap.setLoader(libName -> {
            String fileName = mapLibraryName(libName);
            File libFile = new File(dir, fileName);
            if (libFile.exists()) {
                System.load(libFile.getAbsolutePath());
            } else {
                File found = findLibOnSystem(fileName);
                if (found != null) {
                    try {
                        System.load(found.getAbsolutePath());
                    } catch (UnsatisfiedLinkError e) {
                        if (!e.getMessage().contains("already loaded")) throw e;
                    }
                } else {
                    try {
                        System.loadLibrary(libName);
                    } catch (UnsatisfiedLinkError e) {
                        if (!e.getMessage().contains("already loaded")) throw e;
                    }
                }
            }
        });
    }

    private static String mapLibraryName(String libName) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return libName + ".dll";
        if (os.contains("mac")) return "lib" + libName + ".dylib";
        return "lib" + libName + ".so";
    }

    private static void preloadNativeLibraries(File dir) {
        String os = System.getProperty("os.name").toLowerCase();
        String[] libs;
        if (os.contains("win")) {
            libs = new String[]{
                    "d3dcompiler_47.dll",
                    "libGLESv2.dll",
                    "libEGL.dll",
                    "chrome_elf.dll",
                    "libcef.dll",
                    "jcef.dll"
            };
        } else if (os.contains("linux")) {
            libs = new String[]{"libcef.so", "libjcef.so"};
        } else if (os.contains("mac")) {
            libs = new String[]{"libjcef.dylib"};
        } else {
            libs = new String[0];
        }

        for (String name : libs) {
            File f = new File(dir, name);
            if (f.exists()) {
                try {
                    System.load(f.getAbsolutePath());
                } catch (UnsatisfiedLinkError e) {
                    if (!e.getMessage().contains("already loaded")) {
                        Log.warn("[NativeLoader] Could not preload @: @", name, e.getMessage());
                    }
                }
            }
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String osName;
        if (os.contains("win")) osName = "windows";
        else if (os.contains("mac")) osName = "macos";
        else osName = "linux";

        String archName;
        if (arch.contains("amd64") || arch.contains("x86_64") || (arch.contains("64") && !arch.contains("arm")))
            archName = "amd64";
        else if (arch.contains("aarch64") || arch.contains("arm64"))
            archName = "arm64";
        else
            archName = "x86";

        return osName + "-" + archName;
    }

    private static String findNativeJarName(String platform) {
        ClassLoader cl = NativeLoader.class.getClassLoader();
        String[] candidates = {
                "jcef-natives-" + platform + "-jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123.jar",
                "libs/jcef-natives-" + platform + "-jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123.jar",
                "jcef-natives-" + platform + ".jar",
                "libs/jcef-natives-" + platform + ".jar",
        };
        for (String name : candidates) {
            try (InputStream test = cl.getResourceAsStream(name)) {
                if (test != null) return name;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean extractFromJarStream(InputStream jarStream, File destDir) throws Exception {
        try (JarInputStream jis = new JarInputStream(jarStream)) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                if (entry.getName().endsWith(".tar.gz")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = jis.read(buf)) != -1) baos.write(buf, 0, n);
                    extractTarGz(new ByteArrayInputStream(baos.toByteArray()), destDir);
                    return true;
                }
            }
        }
        return false;
    }

    private static void extractTarGz(InputStream in, File destDir) throws Exception {
        int count = 0;
        try (GZIPInputStream gzis = new GZIPInputStream(new BufferedInputStream(in));
             TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {
            TarArchiveEntry entry;
            while ((entry = tais.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    new File(destDir, entry.getName()).mkdirs();
                    continue;
                }
                File out = new File(destDir, entry.getName());
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = tais.read(buf)) != -1) fos.write(buf, 0, n);
                }
                count++;
            }
        }
        if (count == 0) throw new RuntimeException("tar.gz was empty");
    }
}
