import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.unmojang.loki.transformers.LegacyRconThreadTransformer;

public class LegacyRconThreadTest {
    static boolean observed;
    public static void observe(boolean running) { observed = running; }

    static byte[] fixture(boolean rcon) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "RconFixture", null,
                "java/lang/Object", new String[] { "java/lang/Runnable" });
        writer.visitField(Opcodes.ACC_PUBLIC, "running", "Z", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        run.visitCode(); run.visitInsn(Opcodes.RETURN); run.visitMaxs(0, 0); run.visitEnd();
        MethodVisitor start = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                "start", "()V", null, null);
        start.visitCode();
        if (rcon) {
            start.visitLdcInsn("registerSocket: "); start.visitInsn(Opcodes.POP);
            start.visitLdcInsn("closeSocket: "); start.visitInsn(Opcodes.POP);
        }
        start.visitTypeInsn(Opcodes.NEW, "java/lang/Thread"); start.visitInsn(Opcodes.DUP);
        start.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false);
        start.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        start.visitVarInsn(Opcodes.ALOAD, 0); start.visitInsn(Opcodes.ICONST_1);
        start.visitFieldInsn(Opcodes.PUTFIELD, "RconFixture", "running", "Z");
        start.visitInsn(Opcodes.RETURN); start.visitMaxs(0, 0); start.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }

    static boolean observeAtStart(byte[] bytes) throws Exception {
        ClassNode cn = new ClassNode(); new ClassReader(bytes).accept(cn, 0);
        for (MethodNode method : cn.methods) for (AbstractInsnNode node : method.instructions.toArray()) {
            if (!(node instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) node;
            if (!call.owner.equals("java/lang/Thread") || !call.name.equals("start")) continue;
            InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new FieldInsnNode(Opcodes.GETFIELD, "RconFixture", "running", "Z"));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "LegacyRconThreadTest", "observe", "(Z)V", false));
            method.instructions.insertBefore(call, hook);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); cn.accept(writer);
        final byte[] instrumented = writer.toByteArray();
        Class<?> type = new ClassLoader(LegacyRconThreadTest.class.getClassLoader()) {
            Class<?> define() { return defineClass("RconFixture", instrumented, 0, instrumented.length); }
        }.define();
        observed = false;
        type.getMethod("start").invoke(type.getConstructor().newInstance());
        return observed;
    }

    public static void main(String[] args) throws Exception {
        LegacyRconThreadTransformer transformer = new LegacyRconThreadTransformer();
        byte[] original = fixture(true);
        if (observeAtStart(original)) throw new AssertionError("Fixture must expose the original race");
        byte[] patched = transformer.transform(null, "RconFixture", null, null, original);
        if (patched == null || !observeAtStart(patched)) throw new AssertionError("Worker starts before flag publication");
        ClassNode node = new ClassNode(); new ClassReader(patched).accept(node, 0);
        if ((node.fields.get(0).access & Opcodes.ACC_VOLATILE) == 0) throw new AssertionError("Flag visibility not guaranteed");
        if (transformer.transform(null, "RconFixture", null, null, patched) != null) throw new AssertionError("Repeated transformation");
        if (transformer.transform(null, "RconFixture", null, null, fixture(false)) != null) throw new AssertionError("Unrelated Runnable changed");
        for (String path : args) {
            JarFile jar = new JarFile(path);
            int changed = 0;
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                java.io.InputStream input = jar.getInputStream(entry);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                input.close();
                if (transformer.transform(null, entry.getName().substring(0, entry.getName().length() - 6),
                        null, null, output.toByteArray()) != null) changed++;
            }
            jar.close();
            if (changed != 1) throw new AssertionError("Official artifact matched " + changed + " classes");
        }
        System.out.println("Legacy RCON publishes its volatile running flag before starting its worker");
    }
}
