import java.util.Map;
import org.objectweb.asm.*;
import org.unmojang.loki.RequestInterceptor;
import org.unmojang.loki.hooks.RealmsHooks;
import org.unmojang.loki.transformers.RealmsURLTransformer;

public class RealmsURLTest {
    static byte[] fixture(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "url", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn("mco/client/compatible");
        method.visitInsn(Opcodes.POP);
        method.visitTypeInsn(Opcodes.NEW, "java/net/URI");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("https");
        method.visitLdcInsn("pc.realms.minecraft.net");
        method.visitLdcInsn("/mco/client/compatible");
        method.visitLdcInsn("a=one%20two");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URI", "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URI", "toASCIIString", "()Ljava/lang/String;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> urls = RequestInterceptor.YGGDRASIL_MAP;
        urls.put("pc.realms.minecraft.net", "https://realms.test/base/");
        for (final String name : new String[] { "fby", "com/mojang/realmsclient/client/RealmsClient" }) {
            final byte[] transformed = new RealmsURLTransformer().transform(
                    RealmsURLTest.class.getClassLoader(), name, null, null, fixture(name));
            if (transformed == null) throw new AssertionError("Realms URL not transformed: " + name);
            Class<?> type = new ClassLoader(RealmsURLTest.class.getClassLoader()) {
                Class<?> define() { return defineClass(name.replace('/', '.'), transformed, 0, transformed.length); }
            }.define();
            String result = (String) type.getMethod("url").invoke(null);
            if (!"https://realms.test/base/mco/client/compatible?a=one%2520two".equals(result))
                throw new AssertionError(result);
        }
        if (!"https://other.test/a".equals(RealmsHooks.redirect("https://other.test/a")))
            throw new AssertionError("Unrelated URL changed");
        if (!"https://pc.realms.minecraft.net.evil.test/a".equals(RealmsHooks.redirect("https://pc.realms.minecraft.net.evil.test/a")))
            throw new AssertionError("Suffix domain changed");
        urls.remove("pc.realms.minecraft.net");
        if (!"https://pc.realms.minecraft.net/a".equals(RealmsHooks.redirect("https://pc.realms.minecraft.net/a")))
            throw new AssertionError("Unconfigured URL changed");
        for (String file : args) {
            java.util.zip.ZipFile archive = new java.util.zip.ZipFile(file);
            String name = file.contains("26.2") ? "com/mojang/realmsclient/client/RealmsClient" : "fby";
            java.io.InputStream input = archive.getInputStream(archive.getEntry(name + ".class"));
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            input.close(); archive.close();
            byte[] patched = new RealmsURLTransformer().transform(RealmsURLTest.class.getClassLoader(), name, null, null, output.toByteArray());
            if (patched == null) throw new AssertionError("Official Realms client not patched: " + file);
            final int[] hooks = {0};
            new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if (owner.equals("org/unmojang/loki/hooks/RealmsHooks") && name.equals("redirect")) hooks[0]++;
                        }
                    };
                }
            }, 0);
            if (hooks[0] != 1) throw new AssertionError("Unexpected official URL hook count: " + hooks[0]);
        }
        System.out.println("Realms URL redirects pass without a global URL factory");
    }
}
