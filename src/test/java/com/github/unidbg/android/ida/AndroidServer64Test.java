package com.github.unidbg.android.ida;

import com.github.unidbg.Emulator;
import com.github.unidbg.LibraryResolver;
import com.github.unidbg.Module;
import com.github.unidbg.Symbol;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.debugger.ida.Utils;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.file.linux.AndroidFileIO;
import com.github.unidbg.hook.ReplaceCallback;
import com.github.unidbg.hook.hookzz.*;
import com.github.unidbg.hook.xhook.IxHook;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.XHookImpl;
import com.github.unidbg.linux.file.ByteArrayFileIO;
import com.github.unidbg.linux.file.DirectoryFileIO;
import com.github.unidbg.linux.file.MapsFileIO;
import com.github.unidbg.linux.file.SimpleFileIO;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.pointer.UnicornPointer;
import com.github.unidbg.utils.Inspector;
import com.sun.jna.Pointer;
import org.apache.commons.codec.binary.Hex;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class AndroidServer64Test implements IOResolver<AndroidFileIO> {

    public static void main(String[] args) throws IOException {
        new AndroidServer64Test().test();
    }

    @Override
    public FileResult<AndroidFileIO> resolve(Emulator<AndroidFileIO> emulator, String pathname, int oflags) {
        final int attachPid = emulator.getPid() - 1;
        if (("/proc/" + attachPid + "/maps").equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new MapsFileIO(oflags, pathname, emulator.getMemory().getLoadedModules()));
        }
        if (("/proc/" + attachPid + "/cmdline").equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new ByteArrayFileIO(oflags, pathname, ("/system/bin/" + executable.getName()).getBytes()));
        }
        if (("/proc/" + attachPid + "/task/" + attachPid + "/comm").equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new ByteArrayFileIO(oflags, pathname, (executable.getName() + "\n").getBytes()));
        }
        if (("/proc/" + attachPid + "/exe").equals(pathname) || "/system/bin/android_server64_7.4".equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new SimpleFileIO(oflags, executable, pathname));
        }
        if ("/proc".equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname, new DirectoryFileIO.DirectoryEntry(false, Integer.toString(attachPid))));
        }
        if (("/proc/" + attachPid).equals(pathname)) {
            return FileResult.<AndroidFileIO>success(new DirectoryFileIO(oflags, pathname,
                    new DirectoryFileIO.DirectoryEntry(true, "maps"),
                    new DirectoryFileIO.DirectoryEntry(true, "cmdline"),
                    new DirectoryFileIO.DirectoryEntry(true, "exe"),
                    new DirectoryFileIO.DirectoryEntry(false, "task")));
        }

        return null;
    }

    private final Emulator<AndroidFileIO> emulator;
    private final Module module;
    private final File executable;

    private AndroidServer64Test() throws IOException {
        executable = new File("src/test/resources/example_binaries/ida/android_server64_7.4");
        emulator = new MyAndroidARM64Emulator(executable);
        emulator.getSyscallHandler().addIOResolver(this);
        Memory memory = emulator.getMemory();
        LibraryResolver resolver = new AndroidResolver(23);
        memory.setLibraryResolver(resolver);

        memory.setCallInitFunction();

        module = emulator.loadLibrary(executable);
    }

    private void test() {
        IxHook ixHook = XHookImpl.getInstance(emulator);
        ixHook.register(executable.getName(), "ptrace", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, long originFunction) {
                RegisterContext context = emulator.getContext();
                int request = context.getIntArg(0);
                Pointer addr = context.getPointerArg(2);
                Pointer data = context.getPointerArg(3);
                if (request != PTrace.PTRACE_PEEKTEXT && request != PTrace.PTRACE_POKEDATA) {
                    System.out.println("ptrace request=" + request + ", addr=" + addr + ", data=" + data + ", LR=" + context.getLRPointer());
                }
                return super.onCall(emulator, originFunction);
            }
        });
        ixHook.refresh();

        IHookZz hookZz = HookZz.getInstance(emulator);
        Symbol pack_dd = module.findSymbolByName("pack_dd", false);
        hookZz.wrap(pack_dd, new WrapCallback<HookZzArm64RegisterContext>() {
            @Override
            public void preCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                Pointer data = ctx.getPointerArg(0);
                int value = ctx.getIntArg(2);
                ctx.set("data", data);
                ctx.set("value", value & 0xffffffffL);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                super.postCall(emulator, ctx, info);
                UnicornPointer data = ctx.get("data");
                UnicornPointer end = ctx.getPointerArg(0);
                long value = ctx.get("value");
                int size = (int) (end.toUIntPeer() - data.toUIntPeer());
                byte[] my = Utils.pack_dd(value);
                byte[] ida = data.getByteArray(0, size);
                long unpack = Utils.unpack_dd(ByteBuffer.wrap(ida));
                if (!Arrays.equals(my, ida) || unpack != value) {
                    Inspector.inspect(ida, "pack_dd value=0x" + Long.toHexString(value) + ", unpack=0x" + Long.toHexString(unpack) + ", my=" + Hex.encodeHexString(my));
                }
            }
        });
        Symbol unpack_dd = module.findSymbolByName("unpack_dd", false);
        hookZz.wrap(unpack_dd, new WrapCallback<HookZzArm64RegisterContext>() {
            @Override
            public void preCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                Pointer pointer = ctx.getPointerArg(0);
                Pointer data = pointer.getPointer(0);
                Pointer end = ctx.getPointerArg(1);
                ctx.set("data", data);
                ctx.set("end", end);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookZzArm64RegisterContext ctx, HookEntryInfo info) {
                super.postCall(emulator, ctx, info);
                UnicornPointer data = ctx.get("data");
                UnicornPointer end = ctx.get("end");
                long value = ctx.getXLong(0);
                int size = (int) (end.toUIntPeer() - data.toUIntPeer());
                Inspector.inspect(data.getByteArray(0, size), "unpack_dd data=" + data + ", value=0x" + Long.toHexString(value) + ", LR=" + ctx.getLRPointer());
            }
        });

        Logger.getLogger("com.github.unidbg.AbstractEmulator").setLevel(Level.DEBUG);
        emulator.attach().addBreakPoint(module, 0x000000000002AAE8);

        System.err.println("exit code: " + module.callEntry(emulator));
    }

}