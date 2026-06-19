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
    private static final long CB_OFFSET =
            DATA_BLOB.byteOffset(MemoryLayout.PathElement.groupElement("cbData"));
    private static final long PB_OFFSET =
            DATA_BLOB.byteOffset(MemoryLayout.PathElement.groupElement("pbData"));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIB_ARENA = Arena.ofShared();
    private static final SymbolLookup CRYPT32 = SymbolLookup.libraryLookup("Crypt32", LIB_ARENA);
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("Kernel32", LIB_ARENA);

    private static final MethodHandle CRYPT_PROTECT_DATA = LINKER.downcallHandle(
            CRYPT32.find("CryptProtectData").orElseThrow(
                    () -> new UnsatisfiedLinkError("CryptProtectData not found in Crypt32.dll")),
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    );

    private static final MethodHandle CRYPT_UNPROTECT_DATA = LINKER.downcallHandle(
            CRYPT32.find("CryptUnprotectData").orElseThrow(
                    () -> new UnsatisfiedLinkError("CryptUnprotectData not found in Crypt32.dll")),
            FunctionDescriptor.of(JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    );

    private static final MethodHandle LOCAL_FREE = LINKER.downcallHandle(
            KERNEL32.find("LocalFree").orElseThrow(
                    () -> new UnsatisfiedLinkError("LocalFree not found in Kernel32.dll")),
            FunctionDescriptor.of(ADDRESS, ADDRESS)
    );

    private WindowsDpapi() {}

    public static String protect(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        byte[] in = plaintext.getBytes(StandardCharsets.UTF_8);
        try (var arena = Arena.ofConfined()) {
            MemorySegment inBuf = arena.allocate(in.length);
            MemorySegment.copy(in, 0, inBuf, JAVA_BYTE, 0, in.length);
            MemorySegment inBlob = arena.allocate(DATA_BLOB);
            inBlob.set(JAVA_INT, CB_OFFSET, in.length);
            inBlob.set(ADDRESS, PB_OFFSET, inBuf);

            MemorySegment outBlob = arena.allocate(DATA_BLOB);
            int ok;
            try {
                ok = (int) CRYPT_PROTECT_DATA.invoke(
                        inBlob,
                        MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL,
                        CRYPTPROTECT_UI_FORBIDDEN,
                        outBlob);
            } catch (Throwable t) {
                throw new IllegalStateException("CryptProtectData invocation failed", t);
            }
            if (ok == 0) {
                throw new IllegalStateException("CryptProtectData returned FALSE");
            }
            int outLen = outBlob.get(JAVA_INT, CB_OFFSET);
            MemorySegment outPtr = outBlob.get(ADDRESS, PB_OFFSET).reinterpret(outLen);
            byte[] outBytes = outPtr.toArray(JAVA_BYTE);
            freeQuietly(outPtr);
            return Base64.getEncoder().encodeToString(outBytes);
        }
    }

    public static String unprotect(String ciphertextB64) {
        if (ciphertextB64 == null || ciphertextB64.isEmpty()) return "";
        byte[] in = Base64.getDecoder().decode(ciphertextB64);
        try (var arena = Arena.ofConfined()) {
            MemorySegment inBuf = arena.allocate(in.length);
            MemorySegment.copy(in, 0, inBuf, JAVA_BYTE, 0, in.length);
            MemorySegment inBlob = arena.allocate(DATA_BLOB);
            inBlob.set(JAVA_INT, CB_OFFSET, in.length);
            inBlob.set(ADDRESS, PB_OFFSET, inBuf);

            MemorySegment outBlob = arena.allocate(DATA_BLOB);
            int ok;
            try {
                ok = (int) CRYPT_UNPROTECT_DATA.invoke(
                        inBlob,
                        MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL,
                        CRYPTPROTECT_UI_FORBIDDEN,
                        outBlob);
            } catch (Throwable t) {
                throw new IllegalStateException("CryptUnprotectData invocation failed", t);
            }
            if (ok == 0) {
                throw new IllegalStateException(
                        "CryptUnprotectData returned FALSE — данные зашифрованы другим пользователем или повреждены");
            }
            int outLen = outBlob.get(JAVA_INT, CB_OFFSET);
            MemorySegment outPtr = outBlob.get(ADDRESS, PB_OFFSET).reinterpret(outLen);
            byte[] outBytes = outPtr.toArray(JAVA_BYTE);
            freeQuietly(outPtr);
            return new String(outBytes, StandardCharsets.UTF_8);
        }
    }

    private static void freeQuietly(MemorySegment ptr) {
        try {
            LOCAL_FREE.invoke(ptr);
        } catch (Throwable ignored) {
        }
    }
}
