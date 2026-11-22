import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.jetbrains.org.objectweb.asm.AnnotationVisitor;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.FieldVisitor;
import org.jetbrains.org.objectweb.asm.Label;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.RecordComponentVisitor;
import org.jetbrains.org.objectweb.asm.TypePath;
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper;
import org.jetbrains.org.objectweb.asm.commons.Remapper;

public final class KotlinShader {
  private static final String KOTLIN_PREFIX = "kotlin/";

  private KotlinShader() {}

  public static void main(String[] args) throws Exception {
    List<Path> inDirs = new ArrayList<>();
    List<Path> stdlibJars = new ArrayList<>();
    Path outDir = null;
    String targetPrefix = "cinder/kotlin/";

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--in":
          inDirs.add(Paths.get(requireArg(args, ++i, "--in")));
          break;
        case "--stdlib":
          stdlibJars.add(Paths.get(requireArg(args, ++i, "--stdlib")));
          break;
        case "--out":
          outDir = Paths.get(requireArg(args, ++i, "--out"));
          break;
        case "--prefix":
          targetPrefix = requireArg(args, ++i, "--prefix");
          if (!targetPrefix.endsWith("/")) {
            targetPrefix = targetPrefix + "/";
          }
          break;
        default:
          throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }

    if (inDirs.isEmpty() || stdlibJars.isEmpty() || outDir == null) {
      System.err.println(
          "Usage: KotlinShader --in <dir> [--in <dir>...] --stdlib <jar> [--stdlib <jar>...] "
              + "--out <dir> [--prefix cinder/kotlin/]");
      System.exit(2);
    }

    Map<String, byte[]> stdlibIndex = new HashMap<>();
    for (Path jarPath : stdlibJars) {
      try (JarFile jar = new JarFile(jarPath.toFile())) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          JarEntry entry = entries.nextElement();
          String name = entry.getName();
          if (entry.isDirectory() || !name.endsWith(".class")) {
            continue;
          }

          if (name.startsWith("META-INF/")) {
            continue;
          }
          String internalName = name.substring(0, name.length() - ".class".length());
          if (internalName.startsWith(KOTLIN_PREFIX)) {
            stdlibIndex.putIfAbsent(internalName, readAllBytes(jar.getInputStream(entry)));
          }
        }
      }
    }

    List<byte[]> appClasses = new ArrayList<>();
    for (Path dir : inDirs) {
      if (!Files.isDirectory(dir)) {
        continue;
      }
      List<Path> files = new ArrayList<>();
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class"))
            .sorted()
            .forEach(files::add);
      }
      for (Path f : files) {
        appClasses.add(Files.readAllBytes(f));
      }
    }

    final String prefix = targetPrefix;
    final Deque<String> queue = new ArrayDeque<>();
    final Set<String> seenStdlib = new HashSet<>();
    final Set<String> missingKotlin = new TreeSet<>();

    Remapper remapper =
        new Remapper() {
          @Override
          public String map(String internalName) {
            if (internalName != null && internalName.startsWith(KOTLIN_PREFIX)) {
              if (seenStdlib.add(internalName)) {
                if (stdlibIndex.containsKey(internalName)) {
                  queue.addLast(internalName);
                } else {
                  missingKotlin.add(internalName);
                }
              }
              return prefix + internalName.substring(KOTLIN_PREFIX.length());
            }
            return internalName;
          }
        };

    Files.createDirectories(outDir);

    int appCount = 0;
    for (byte[] raw : appClasses) {
      RemappedClass rc = remapClass(raw, remapper);
      writeClass(outDir, rc.internalName, rc.bytes);
      appCount++;
    }

    int stdlibCount = 0;
    while (!queue.isEmpty()) {
      String kotlinName = queue.removeFirst();
      byte[] raw = stdlibIndex.get(kotlinName);
      if (raw == null) {
        missingKotlin.add(kotlinName);
        continue;
      }
      RemappedClass rc = remapClass(raw, remapper);
      writeClass(outDir, rc.internalName, rc.bytes);
      stdlibCount++;
    }

    if (!missingKotlin.isEmpty()) {
      System.err.println(
          "ERROR: KotlinShader found referenced kotlin/* classes missing from stdlib jar(s):");
      for (String m : missingKotlin) {
        System.err.println("  - " + m);
      }
      System.exit(1);
    }

    System.out.println(
        "KotlinShader: remapped "
            + appCount
            + " app class(es) + "
            + stdlibCount
            + " shaded stdlib class(es) ("
            + KOTLIN_PREFIX
            + "* -> "
            + prefix
            + "*)");
  }

  private static String requireArg(String[] args, int idx, String flag) {
    if (idx >= args.length) {
      throw new IllegalArgumentException("Missing value for " + flag);
    }
    return args[idx];
  }

  private static byte[] readAllBytes(InputStream in) throws IOException {
    try (InputStream s = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = s.read(buf)) >= 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static void writeClass(Path outDir, String internalName, byte[] bytes)
      throws IOException {
    Path dst = outDir.resolve(internalName + ".class");
    Files.createDirectories(dst.getParent());
    Files.write(dst, bytes);
  }

  private static RemappedClass remapClass(byte[] raw, Remapper remapper) {
    ClassReader reader = new ClassReader(raw);
    ClassWriter writer = new ClassWriter(0);
    String originalName = reader.getClassName();
    String mappedName = remapper.mapType(originalName);
    ClassRemapper classRemapper = new ClassRemapper(writer, remapper);
    AnnotationStripper stripper = new AnnotationStripper(classRemapper);
    reader.accept(stripper, 0);
    return new RemappedClass(mappedName, writer.toByteArray());
  }

  private static final class RemappedClass {
    final String internalName;
    final byte[] bytes;

    RemappedClass(String internalName, byte[] bytes) {
      this.internalName = internalName;
      this.bytes = bytes;
    }
  }

  private static boolean shouldStripAnnotation(String descriptor) {
    if (descriptor == null) {
      return false;
    }
    return descriptor.equals("Lkotlin/Metadata;")
        || descriptor.equals("Lkotlin/jvm/internal/SourceDebugExtension;")
        || descriptor.startsWith("Lorg/jetbrains/annotations/");
  }

  private static final class AnnotationStripper extends ClassVisitor {
    AnnotationStripper(ClassVisitor cv) {
      super(Opcodes.ASM9, cv);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
      if (shouldStripAnnotation(descriptor)) {
        return null;
      }
      return super.visitAnnotation(descriptor, visible);
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(
        int typeRef, TypePath typePath, String descriptor, boolean visible) {
      if (shouldStripAnnotation(descriptor)) {
        return null;
      }
      return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
    }

    @Override
    public RecordComponentVisitor visitRecordComponent(
        String name, String descriptor, String signature) {
      RecordComponentVisitor rcv = super.visitRecordComponent(name, descriptor, signature);
      if (rcv == null) {
        return null;
      }
      return new RecordComponentVisitor(Opcodes.ASM9, rcv) {
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
          return shouldStripAnnotation(desc) ? null : super.visitAnnotation(desc, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
            int typeRef, TypePath typePath, String desc, boolean visible) {
          return shouldStripAnnotation(desc)
              ? null
              : super.visitTypeAnnotation(typeRef, typePath, desc, visible);
        }
      };
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
      if (fv == null) {
        return null;
      }
      return new FieldVisitor(Opcodes.ASM9, fv) {
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
          return shouldStripAnnotation(desc) ? null : super.visitAnnotation(desc, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
            int typeRef, TypePath typePath, String desc, boolean visible) {
          return shouldStripAnnotation(desc)
              ? null
              : super.visitTypeAnnotation(typeRef, typePath, desc, visible);
        }
      };
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (mv == null) {
        return null;
      }
      return new MethodVisitor(Opcodes.ASM9, mv) {
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
          return shouldStripAnnotation(desc) ? null : super.visitAnnotation(desc, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
            int typeRef, TypePath typePath, String desc, boolean visible) {
          return shouldStripAnnotation(desc)
              ? null
              : super.visitTypeAnnotation(typeRef, typePath, desc, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(
            int parameter, String desc, boolean visible) {
          return shouldStripAnnotation(desc)
              ? null
              : super.visitParameterAnnotation(parameter, desc, visible);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(
            int typeRef, TypePath typePath, String desc, boolean visible) {
          return shouldStripAnnotation(desc)
              ? null
              : super.visitInsnAnnotation(typeRef, typePath, desc, visible);
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(
            int typeRef, TypePath typePath, String desc, boolean visible) {
          return shouldStripAnnotation(desc)
              ? null
              : super.visitTryCatchAnnotation(typeRef, typePath, desc, visible);
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(
            int typeRef,
            TypePath typePath,
            Label[] start,
            Label[] end,
            int[] index,
            String desc,
            boolean visible) {
          return shouldStripAnnotation(desc)
              ? null
              : super.visitLocalVariableAnnotation(
                  typeRef, typePath, start, end, index, desc, visible);
        }
      };
    }
  }
}
