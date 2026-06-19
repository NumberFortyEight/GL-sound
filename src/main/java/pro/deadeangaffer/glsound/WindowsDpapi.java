package pro.deadeangaffer.glsound;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class WindowsDpapi {

    public static final String PREFIX = "dpapi:";

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private static final MemoryLayout DATA_BLOB = MemoryLayout.structLayout(
            JAVA_INT.withName("cbData"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pbData")
    );
    private static final long CB_OFFSET = DATA_BLOB.byteOffset(MemoryLayout.PathElement.groupElement("cbData"));
    private static final long PB_OFFSET = DATA_BLOB.byteOffset(MemoryLayout.PathElement.groupElement("pbData"));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIB_ARENA = Arena.ofShared();
    private static final SymbolLookup CRYPT32 = SymbolLookup.libraryLookup("Crypt32", LIB_ARENA);
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("Kernel32", LIB_ARENA);

    private static final MethodHandle CRYPT_PROTECT_DATA = downcall(CRYPT32, "CryptProtectData");
    private static final MethodHandle CRYPT_UNPROTECT_DATA = downcall(CRYPT32, "CryptUnprotectData");
    private static final MethodHandle LOCAL_FREE = LINKER.downcallHandle(
            KERNEL32.find("LocalFree")
                    .orElseThrow(() -> new UnsatisfiedLinkError("LocalFree not found in Kernel32.dll")),
            FunctionDescriptor.of(ADDRESS, ADDRESS)
    );

    private WindowsDpapi() {}

    public static String protect(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        byte[] in = plaintext.getBytes(StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            byte[] outBytes = crypt(CRYPT_PROTECT_DATA, arena, in, "CryptProtectData");
            return Base64.getEncoder().encodeToString(outBytes);
        }
    }

    public static String unprotect(String ciphertextB64) {
        if (ciphertextB64 == null || ciphertextB64.isEmpty()) return "";
        byte[] in = Base64.getDecoder().decode(ciphertextB64);
        try (Arena arena = Arena.ofConfined()) {
            byte[] outBytes = crypt(CRYPT_UNPROTECT_DATA, arena, in, "CryptUnprotectData");
            return new String(outBytes, StandardCharsets.UTF_8);
        }
    }

    private static MethodHandle downcall(SymbolLookup lib, String name) {
        return LINKER.downcallHandle(
                lib.find(name)
                        .orElseThrow(() -> new UnsatisfiedLinkError("%s not found".formatted(name))),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        );
    }

    private static byte[] crypt(MethodHandle handle, Arena arena, byte[] in, String name) {
        MemorySegment inBuf = arena.allocate(in.length);
        MemorySegment.copy(in, 0, inBuf, JAVA_BYTE, 0, in.length);
        MemorySegment inBlob = arena.allocate(DATA_BLOB);
        inBlob.set(JAVA_INT, CB_OFFSET, in.length);
        inBlob.set(ADDRESS, PB_OFFSET, inBuf);
        MemorySegment outBlob = arena.allocate(DATA_BLOB);
        int ok;
        try {
            ok = (int) handle.invoke(
                    inBlob,
                    MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    outBlob);
        } catch (Throwable t) {
            throw new IllegalStateException("%s invocation failed".formatted(name), t);
        }
        if (ok == 0) throw new IllegalStateException("%s returned FALSE — данные повреждены или зашифрованы другим пользователем".formatted(name));
        int outLen = outBlob.get(JAVA_INT, CB_OFFSET);
        MemorySegment outPtr = outBlob.get(ADDRESS, PB_OFFSET).reinterpret(outLen);
        byte[] outBytes = outPtr.toArray(JAVA_BYTE);
        freeQuietly(outPtr);
        return outBytes;
    }

    private static void freeQuietly(MemorySegment ptr) {
        try {
            LOCAL_FREE.invoke(ptr);
        } catch (Throwable ignored) {
        }
    }
}
