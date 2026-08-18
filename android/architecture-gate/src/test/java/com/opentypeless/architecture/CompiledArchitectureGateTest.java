package com.opentypeless.architecture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** End-to-end tests for the compiled editor-capability boundary. */
public final class CompiledArchitectureGateTest {
    private static Path workspace;
    private static Path androidAndThirdPartyClasses;
    private static JavaCompiler compiler;

    @BeforeClass
    public static void compileAndroidAndThirdPartyFixtures() throws Exception {
        workspace = Files.createTempDirectory("compiled-architecture-gate-");
        compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue("The architecture gate tests require a JDK, not a JRE", compiler != null);
        androidAndThirdPartyClasses = workspace.resolve("all-dependencies");
        compile(
                androidAndThirdPartyClasses,
                Map.ofEntries(
                        source(
                                "android.view.inputmethod.InputConnection",
                                """
                                package android.view.inputmethod;

                                public interface InputConnection {
                                    boolean beginBatchEdit();
                                    boolean commitText(CharSequence text, int cursor);
                                    void closeConnection();
                                    boolean deleteSurroundingText(int before, int after);
                                    boolean deleteSurroundingTextInCodePoints(int before, int after);
                                    boolean endBatchEdit();
                                    boolean finishComposingText();
                                    android.view.inputmethod.ExtractedText getExtractedText(
                                            android.view.inputmethod.ExtractedTextRequest request,
                                            int flags);
                                    CharSequence getSelectedText(int flags);
                                    CharSequence getTextAfterCursor(int length, int flags);
                                    CharSequence getTextBeforeCursor(int length, int flags);
                                    boolean performEditorAction(int action);
                                    boolean performPrivateCommand(String action, android.os.Bundle data);
                                    boolean sendKeyEvent(android.view.KeyEvent event);
                                    boolean setComposingRegion(int start, int end);
                                    boolean setComposingText(CharSequence text, int cursor);
                                    boolean setComposingText(
                                            CharSequence text,
                                            int cursor,
                                            android.view.inputmethod.TextAttribute attribute);
                                    boolean setSelection(int start, int end);
                                    boolean requestCursorUpdates(int mode);
                                    boolean requestCursorUpdates(int mode, int filter);
                                }
                                """),
                        source(
                                "android.view.inputmethod.InputConnectionWrapper",
                                """
                                package android.view.inputmethod;

                                public class InputConnectionWrapper implements InputConnection {
                                    public boolean beginBatchEdit() { return true; }
                                    public boolean commitText(CharSequence text, int cursor) { return true; }
                                    public void closeConnection() {}
                                    public boolean deleteSurroundingText(int before, int after) { return true; }
                                    public boolean deleteSurroundingTextInCodePoints(int before, int after) { return true; }
                                    public boolean endBatchEdit() { return true; }
                                    public boolean finishComposingText() { return true; }
                                    public android.view.inputmethod.ExtractedText getExtractedText(
                                            android.view.inputmethod.ExtractedTextRequest request,
                                            int flags) { return null; }
                                    public CharSequence getSelectedText(int flags) { return ""; }
                                    public CharSequence getTextAfterCursor(int length, int flags) { return ""; }
                                    public CharSequence getTextBeforeCursor(int length, int flags) { return ""; }
                                    public boolean performEditorAction(int action) { return true; }
                                    public boolean performPrivateCommand(String action, android.os.Bundle data) { return true; }
                                    public boolean sendKeyEvent(android.view.KeyEvent event) { return true; }
                                    public boolean setComposingRegion(int start, int end) { return true; }
                                    public boolean setComposingText(CharSequence text, int cursor) { return true; }
                                    public boolean setComposingText(
                                            CharSequence text,
                                            int cursor,
                                            android.view.inputmethod.TextAttribute attribute) {
                                        return true;
                                    }
                                    public boolean setSelection(int start, int end) { return true; }
                                    public boolean requestCursorUpdates(int mode) { return true; }
                                    public boolean requestCursorUpdates(int mode, int filter) { return true; }
                                }
                                """),
                        source(
                                "android.view.inputmethod.ExtractedText",
                                """
                                package android.view.inputmethod;
                                public final class ExtractedText {}
                                """),
                        source(
                                "android.view.inputmethod.ExtractedTextRequest",
                                """
                                package android.view.inputmethod;
                                public final class ExtractedTextRequest {}
                                """),
                        source(
                                "android.view.inputmethod.TextAttribute",
                                """
                                package android.view.inputmethod;
                                public final class TextAttribute {}
                                """),
                        source(
                                "android.view.inputmethod.EditorInfo",
                                """
                                package android.view.inputmethod;
                                public final class EditorInfo {}
                                """),
                        source(
                                "android.view.inputmethod.BaseInputConnection",
                                """
                                package android.view.inputmethod;

                                public class BaseInputConnection extends InputConnectionWrapper {}
                                """),
                        source(
                                "android.view.inputmethod.InputBinding",
                                """
                                package android.view.inputmethod;

                                public final class InputBinding {
                                    private final InputConnection connection;
                                    public InputBinding(InputConnection connection) { this.connection = connection; }
                                    public InputConnection getConnection() { return connection; }
                                }
                                """),
                        source(
                                "android.inputmethodservice.InputMethodService",
                                """
                                package android.inputmethodservice;

                                import android.view.inputmethod.InputBinding;
                                import android.view.inputmethod.InputConnection;

                                public class InputMethodService {
                                    public InputConnection getCurrentInputConnection() { return null; }
                                    public InputBinding getCurrentInputBinding() { return null; }
                                    public final void finishConnectionlessStylusHandwriting(CharSequence text) {}
                                    public final void finishStylusHandwriting() {}
                                    public void onExtractedCursorMovement(int horizontal, int vertical) {}
                                    public void onExtractedSelectionChanged(int start, int end) {}
                                    public boolean onExtractTextContextMenuItem(int id) { return true; }
                                    public boolean sendDefaultEditorAction(boolean fromEnterKey) { return true; }
                                    public void sendDownUpKeyEvents(int keyCode) {}
                                    public void sendKeyChar(char character) {}
                                }
                                """),
                        source(
                                "android.os.Bundle",
                                """
                                package android.os;
                                public final class Bundle {}
                                """),
                        source(
                                "android.view.KeyEvent",
                                """
                                package android.view;
                                public final class KeyEvent {}
                                """),
                        source(
                                "android.widget.Spinner",
                                """
                                package android.widget;

                                public class Spinner {
                                    public void setSelection(int position) {}
                                }
                                """),
                        source(
                                "android.os.SystemClock",
                                """
                                package android.os;

                                public final class SystemClock {
                                    private SystemClock() {}
                                    public static long elapsedRealtime() { return 0L; }
                                }
                                """),
                        source(
                                "android.os.Parcelable",
                                """
                                package android.os;
                                public interface Parcelable {}
                                """),
                        source(
                                "androidx.lifecycle.ViewModel",
                                """
                                package androidx.lifecycle;
                                public class ViewModel {
                                    public ViewModel() {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.OpenTypelessImeService",
                                """
                                package com.opentypeless.android.ime;
                                public class OpenTypelessImeService
                                        extends android.inputmethodservice.InputMethodService {}
                                """),
                        source(
                                "third.party.DeepInputWrapper",
                                """
                                package third.party;

                                public class DeepInputWrapper
                                        extends android.view.inputmethod.InputConnectionWrapper {}
                                """)),
                List.of());
    }

    @AfterClass
    public static void deleteWorkspace() throws Exception {
        if (workspace == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(CompiledArchitectureGateTest::deleteQuietly);
        }
    }

    @Test
    public void projectCannotHoldInputConnectionOrSubclassInputMethodService() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.provider.BadProvider",
                                """
                                package com.opentypeless.android.provider;
                                final class BadProvider {
                                    android.view.inputmethod.InputConnection connection;
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.BadIme",
                                """
                                package com.opentypeless.android.provider;
                                final class BadIme extends android.inputmethodservice.InputMethodService {}
                                """)));

        GateResult result = audit(project);

        result.assertFailureContains("BadProvider");
        result.assertFailureContains("BadIme");
        result.assertFailureContains("CAPABILITY");
    }

    @Test
    public void projectCannotObtainCapabilityThroughAllScopeThirdPartyHierarchy() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.provider.IndirectWrapper",
                                """
                                package com.opentypeless.android.provider;
                                final class IndirectWrapper extends third.party.DeepInputWrapper {
                                    boolean write() { return commitText("unsafe", 1); }
                                }
                                """)));

        GateResult result = audit(project);

        result.assertFailureContains("IndirectWrapper");
        result.assertFailureContains("WRITE");
    }

    @Test
    public void bareInheritedWriteAndMethodReferenceAreRejected() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.provider.BareWriter",
                                """
                                package com.opentypeless.android.provider;
                                final class BareWriter extends android.view.inputmethod.InputConnectionWrapper {
                                    boolean write() { return commitText("unsafe", 1); }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.MethodReferenceWriter",
                                """
                                package com.opentypeless.android.provider;
                                import java.util.function.BiFunction;
                                final class MethodReferenceWriter {
                                    BiFunction<CharSequence, Integer, Boolean> writer(
                                            android.view.inputmethod.InputConnection connection) {
                                        return connection::commitText;
                                    }
                                }
                                """)));

        GateResult result = audit(project);

        result.assertFailureContains("BareWriter");
        result.assertFailureContains("MethodReferenceWriter");
        result.assertFailureContains("commitText");
    }

    @Test
    public void inputMethodServiceIndirectSendHelpersAreEditorWriteSinks() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.ShortcutIme",
                                """
                                package com.opentypeless.android.ime;
                                final class ShortcutIme
                                        extends android.inputmethodservice.InputMethodService {
                                    interface CharSender { void send(char value); }
                                    void write() {
                                        finishConnectionlessStylusHandwriting("text");
                                        finishStylusHandwriting();
                                        onExtractedCursorMovement(1, 0);
                                        onExtractedSelectionChanged(1, 1);
                                        onExtractTextContextMenuItem(16908320);
                                        sendDefaultEditorAction(false);
                                        sendDownUpKeyEvents(66);
                                        sendKeyChar('x');
                                    }
                                    CharSender writer() { return this::sendKeyChar; }
                                }
                                """)));

        GateResult result = audit(project);

        result.assertFailureContains("ShortcutIme");
        result.assertFailureContains("EDITOR_WRITE_OWNER");
        result.assertFailureContains("finishConnectionlessStylusHandwriting");
        result.assertFailureContains("finishStylusHandwriting");
        result.assertFailureContains("onExtractedCursorMovement");
        result.assertFailureContains("onExtractedSelectionChanged");
        result.assertFailureContains("onExtractTextContextMenuItem");
        result.assertFailureContains("sendDefaultEditorAction");
        result.assertFailureContains("sendDownUpKeyEvents");
        result.assertFailureContains("sendKeyChar");
    }

    @Test
    public void arraysGenericSignaturesAndInputBindingAccessorAreRejected() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.provider.ArrayLeak",
                                """
                                package com.opentypeless.android.provider;
                                final class ArrayLeak {
                                    android.view.inputmethod.InputConnection[] values;
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.SignatureLeak",
                                """
                                package com.opentypeless.android.provider;
                                import java.util.List;
                                final class SignatureLeak {
                                    List<android.view.inputmethod.InputConnection> values;
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.BindingAccessor",
                                """
                                package com.opentypeless.android.provider;
                                final class BindingAccessor {
                                    Object leak(android.inputmethodservice.InputMethodService service) {
                                        return service.getCurrentInputBinding();
                                    }
                                }
                                """)));

        GateResult result = audit(project);

        result.assertFailureContains("ArrayLeak");
        result.assertFailureContains("SignatureLeak");
        result.assertFailureContains("BindingAccessor");
        result.assertFailureContains("ACCESSOR");
    }

    @Test
    public void exactRegistryOwnerAndRealNestedClassAreAllowed() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.InputConnectionRegistry",
                                """
                                package com.opentypeless.android.editor.host;
                                import android.view.inputmethod.InputConnection;
                                interface InputConnectionRegistry {
                                    InputConnection resolve(long token);
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.ProcessInputConnectionRegistry",
                                """
                                package com.opentypeless.android.editor.host;
                                import android.view.inputmethod.InputConnection;
                                final class ProcessInputConnectionRegistry implements InputConnectionRegistry {
                                    private InputConnection current;
                                    static final class Snapshot {
                                        private InputConnection connection;
                                    }
                                    public InputConnection resolve(long token) { return current; }
                                }
                                """)));

        audit(project).assertSuccess();
    }

    @Test
    public void exactEditorSessionManagerMayHoldButNeverWriteInputConnection() throws Exception {
        Path allowed = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    private android.view.inputmethod.InputConnection observed;
                                }
                                """)));
        audit(allowed).assertSuccess();

        for (String body : List.of(
                "connection.commitText(\"x\", 1);",
                "connection.setComposingText(\"x\", 1);",
                "connection.finishComposingText();",
                "connection.deleteSurroundingText(1, 0);")) {
            Path writer = compileProject(
                    Map.ofEntries(
                            source(
                                    "com.opentypeless.android.editor.host.EditorSessionManager",
                                    """
                                    package com.opentypeless.android.editor.host;
                                    public final class EditorSessionManager {
                                        void write(android.view.inputmethod.InputConnection connection) {
                                            %s
                                        }
                                    }
                                    """.formatted(body))));
            GateResult result = audit(writer);
            result.assertFailureContains("EDITOR_WRITE");
            result.assertFailureContains("EditorSessionManager");
        }
    }

    @Test
    public void exactPackagePrivateFinalTransactionManagerHasOnlyTheAuditedWriteSurface()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    static boolean beginBatch(
                                            android.view.inputmethod.InputConnection connection) {
                                        return connection.beginBatchEdit();
                                    }

                                    static void finishBatch(
                                            android.view.inputmethod.InputConnection connection) {
                                        connection.endBatchEdit();
                                    }

                                    static boolean invokeMutator(
                                            android.view.inputmethod.InputConnection connection,
                                            com.opentypeless.android.editor.EditorOperation operation) {
                                        return connection.commitText("safe", 1)
                                                && connection.deleteSurroundingTextInCodePoints(1, 0)
                                                && connection.performEditorAction(1)
                                                && connection.setComposingText("composition", 1)
                                                && connection.finishComposingText();
                                    }
                                }
                                """)));

        audit(project).assertSuccess();
    }

    @Test
    public void transactionManagerRejectsKeyEventsOverloadsAndEveryOtherMutator()
            throws Exception {
        for (String body : List.of(
                "connection.sendKeyEvent(null);",
                "connection.setComposingText(\"x\", 1, null);",
                "connection.setComposingRegion(0, 1);",
                "connection.deleteSurroundingText(1, 0);",
                "connection.setSelection(0, 0);",
                "connection.performPrivateCommand(\"x\", null);",
                "connection.requestCursorUpdates(1);",
                "connection.closeConnection();")) {
            Path project = compileProject(
                    Map.ofEntries(
                            source(
                                    "com.opentypeless.android.editor.host.EditorTransactionManager",
                                    """
                                    package com.opentypeless.android.editor.host;
                                    final class EditorTransactionManager {
                                        void apply(android.view.inputmethod.InputConnection connection) {
                                            %s
                                        }
                                    }
                                    """.formatted(body))));

            GateResult result = audit(project);
            result.assertFailureContains("EditorTransactionManager");
            result.assertFailureContains("EDITOR_TRANSACTION_WRITE_SURFACE");
        }
    }

    @Test
    public void compositionWritesMustRemainInTheExactTransactionDispatcher() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    static boolean compositionHelper(
                                            android.view.inputmethod.InputConnection connection) {
                                        return connection.setComposingText("unsafe helper", 1)
                                                && connection.finishComposingText();
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("compositionHelper");
        result.assertFailureContains("EDITOR_TRANSACTION_COMPOSITION_METHOD_SURFACE");
    }

    @Test
    public void transactionManagerCannotHideAllowedNamesBehindWrapperOrMethodReference()
            throws Exception {
        Path wrapper = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    boolean apply(android.view.inputmethod.InputConnectionWrapper connection) {
                                        return connection.setComposingText("unsafe", 1)
                                                && connection.finishComposingText();
                                    }
                                }
                                """)));
        audit(wrapper).assertFailureContains("EDITOR_TRANSACTION_WRITE_SURFACE");

        Path reference = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                import java.util.function.Supplier;
                                final class EditorTransactionManager {
                                    Supplier<Boolean> writer(
                                            android.view.inputmethod.InputConnection connection) {
                                        return connection::finishComposingText;
                                    }
                                }
                                """)));
        audit(reference).assertFailureContains("EDITOR_TRANSACTION_WRITE_SURFACE");
    }

    @Test
    public void transactionWriterPermissionIsNotInheritedByNestmateDollarNameOrHostPackage()
            throws Exception {
        Path nested = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    static final class HiddenWriter {
                                        boolean apply(android.view.inputmethod.InputConnection connection) {
                                            return connection.setComposingText("unsafe", 1)
                                                    && connection.finishComposingText();
                                        }
                                    }
                                }
                                """)));
        GateResult nestedResult = audit(nested);
        nestedResult.assertFailureContains("EditorTransactionManager$HiddenWriter");
        nestedResult.assertFailureContains("EDITOR_WRITE_OWNER");

        for (Map.Entry<String, String> fixture : List.of(
                source(
                        "com.opentypeless.android.editor.host.EditorTransactionManager$Evil",
                        """
                        package com.opentypeless.android.editor.host;
                        final class EditorTransactionManager$Evil {
                            boolean apply(android.view.inputmethod.InputConnection connection) {
                                return connection.setComposingText("unsafe", 1)
                                        && connection.finishComposingText();
                            }
                        }
                        """),
                source(
                        "com.opentypeless.android.editor.host.ShortcutWriter",
                        """
                        package com.opentypeless.android.editor.host;
                        final class ShortcutWriter {
                            boolean apply(android.view.inputmethod.InputConnection connection) {
                                return connection.setComposingText("unsafe", 1)
                                        && connection.finishComposingText();
                            }
                        }
                        """),
                source(
                        "com.opentypeless.android.recognition.BadCompositionProvider",
                        """
                        package com.opentypeless.android.recognition;
                        final class BadCompositionProvider {
                            boolean apply(android.view.inputmethod.InputConnection connection) {
                                return connection.setComposingText("unsafe", 1)
                                        && connection.finishComposingText();
                            }
                        }
                        """))) {
            Path project = compileProject(Map.ofEntries(fixture));
            GateResult result = audit(project);
            result.assertFailureContains("EDITOR_WRITE_OWNER");
            result.assertFailureContains(fixture.getKey().substring(fixture.getKey().lastIndexOf('.') + 1));
        }
    }

    @Test
    public void transactionManagerCannotStoreOrReturnInputConnection() throws Exception {
        Path direct = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    private android.view.inputmethod.InputConnection retained;
                                    android.view.inputmethod.InputConnection leak() { return retained; }
                                }
                                """)));
        GateResult directResult = audit(direct);
        directResult.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_STORAGE");
        directResult.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_RETURN");

        Path nested = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    static final class Holder {
                                        android.view.inputmethod.InputConnection retained;
                                    }
                                }
                                """)));
        GateResult nestedResult = audit(nested);
        nestedResult.assertFailureContains("EditorTransactionManager$Holder");
        nestedResult.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_STORAGE");
    }

    @Test
    public void transactionManagerCannotEraseConnectionIntoObjectField() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    private Object retained;
                                    void hold(android.view.inputmethod.InputConnection connection) {
                                        retained = connection;
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_STORAGE");
    }

    @Test
    public void transactionManagerCannotEraseConnectionIntoObjectReturn() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    Object leak(android.view.inputmethod.InputConnection connection) {
                                        return connection;
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_RETURN");
    }

    @Test
    public void transactionManagerCannotEraseConnectionIntoThirdPartyArgument() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    void leak(android.view.inputmethod.InputConnection connection) {
                                        third.party.ObjectSink.accept((Object) connection);
                                    }
                                }
                                """),
                        source(
                                "third.party.ObjectSink",
                                """
                                package third.party;
                                public final class ObjectSink {
                                    public static void accept(Object value) {}
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_TRANSFER");
    }

    @Test
    public void transactionManagerNestmatesCannotEraseOrTransferConnection() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    static final class Holder {
                                        private Object retained;

                                        void fieldLeak(
                                                android.view.inputmethod.InputConnection connection) {
                                            retained = connection;
                                        }

                                        Object returnLeak(
                                                android.view.inputmethod.InputConnection connection) {
                                            return connection;
                                        }

                                        void transferLeak(
                                                android.view.inputmethod.InputConnection connection) {
                                            third.party.ObjectSink.accept((Object) connection);
                                        }
                                    }
                                }
                                """),
                        source(
                                "third.party.ObjectSink",
                                """
                                package third.party;
                                public final class ObjectSink {
                                    public static void accept(Object value) {}
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EditorTransactionManager$Holder");
        result.assertFailureContains("fieldLeak");
        result.assertFailureContains("returnLeak");
        result.assertFailureContains("transferLeak");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_STORAGE");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_RETURN");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_TRANSFER");
    }

    @Test
    public void capturingScopedConnectionUseCannotEscapeImmediateHostLeaseConsumption()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorSessionManager {
                                    @FunctionalInterface
                                    interface ScopedConnectionUse {
                                        Object use(android.view.inputmethod.InputConnection current);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    private Object retained;
                                    private final Object[] retainedArray = new Object[1];

                                    EditorSessionManager.ScopedConnectionUse returnLeak(
                                            android.view.inputmethod.InputConnection connection) {
                                        return current -> Boolean.valueOf(current == connection);
                                    }

                                    void fieldLeak(android.view.inputmethod.InputConnection connection) {
                                        retained = (EditorSessionManager.ScopedConnectionUse)
                                                current -> Boolean.valueOf(current == connection);
                                    }

                                    void arrayLeak(android.view.inputmethod.InputConnection connection) {
                                        retainedArray[0] = (EditorSessionManager.ScopedConnectionUse)
                                                current -> Boolean.valueOf(current == connection);
                                    }

                                    void transferLeak(android.view.inputmethod.InputConnection connection) {
                                        third.party.ObjectSink.accept(
                                                (EditorSessionManager.ScopedConnectionUse)
                                                        current -> Boolean.valueOf(
                                                                current == connection));
                                    }
                                }
                                """),
                        source(
                                "third.party.ObjectSink",
                                """
                                package third.party;
                                public final class ObjectSink {
                                    public static void accept(Object value) {}
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("returnLeak");
        result.assertFailureContains("fieldLeak");
        result.assertFailureContains("arrayLeak");
        result.assertFailureContains("transferLeak");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_RETURN");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_STORAGE");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_TRANSFER");
    }

    @Test
    public void transactionManagerCannotCastAnUntrustedObjectIntoWriteCapability()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    boolean invokeMutator(
                                            android.view.inputmethod.InputConnection ignored,
                                            com.opentypeless.android.editor.EditorOperation operation) {
                                        android.view.inputmethod.InputConnection stolen =
                                                (android.view.inputmethod.InputConnection)
                                                        third.party.ObjectSource.get();
                                        return stolen.commitText("same baseline edge", 1);
                                    }
                                }
                                """),
                        source(
                                "third.party.ObjectSource",
                                """
                                package third.party;
                                public final class ObjectSource {
                                    public static Object get() { return null; }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("invokeMutator");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_PROVENANCE");
    }

    @Test
    public void transactionManagerCannotCastAnUntrustedObjectIntoCapabilityArray()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    boolean invokeMutator(
                                            android.view.inputmethod.InputConnection ignored,
                                            com.opentypeless.android.editor.EditorOperation operation) {
                                        android.view.inputmethod.InputConnection[] stolen =
                                                (android.view.inputmethod.InputConnection[])
                                                        third.party.ObjectSource.get();
                                        return stolen[0].commitText("same baseline edge", 1);
                                    }
                                }
                                """),
                        source(
                                "third.party.ObjectSource",
                                """
                                package third.party;
                                public final class ObjectSource {
                                    public static Object get() { return null; }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("invokeMutator");
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_PROVENANCE");
    }

    @Test
    public void editorSessionManagerMayPassOnlyScopedConnectionIntoExactTransactionManager()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    static boolean invokeMutator(
                                            android.view.inputmethod.InputConnection connection,
                                            com.opentypeless.android.editor.EditorOperation operation) {
                                        return connection.commitText("safe", 1);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    void apply(
                                            EditorTransactionManager transaction,
                                            android.view.inputmethod.InputConnection connection,
                                            com.opentypeless.android.editor.EditorOperation operation) {
                                        transaction.invokeMutator(connection, operation);
                                    }
                                }
                                """)));

        audit(project).assertSuccess();
    }

    @Test
    public void transactionCapabilityCannotMoveToOtherHostProviderOrLegacyAdapter()
            throws Exception {
        Path otherHost = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.TransactionHelper",
                                """
                                package com.opentypeless.android.editor.host;
                                final class TransactionHelper {
                                    EditorTransactionManager transaction;
                                }
                                """)));
        audit(otherHost).assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_TRANSFER");

        Path external = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorTransactionManager {
                                    public void apply(android.view.inputmethod.InputConnection connection) {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.BadProvider",
                                """
                                package com.opentypeless.android.provider;
                                final class BadProvider {
                                    com.opentypeless.android.editor.host.EditorTransactionManager transaction;
                                }
                                """),
                        source(
                                "com.opentypeless.android.speech.delivery.AndroidInputConnectionAdapter",
                                """
                                package com.opentypeless.android.speech.delivery;
                                public final class AndroidInputConnectionAdapter {
                                    void relay(
                                            com.opentypeless.android.editor.host.EditorTransactionManager transaction,
                                            android.view.inputmethod.InputConnection connection) {
                                        transaction.apply(connection);
                                    }
                                }
                                """)));
        GateResult externalResult = audit(external);
        externalResult.assertFailureContains("BadProvider");
        externalResult.assertFailureContains("AndroidInputConnectionAdapter");
        externalResult.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_TRANSFER");
    }

    @Test
    public void transactionManagerCannotResolveRetainOrIndirectlySendThroughIme() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.InputConnectionRegistry",
                                """
                                package com.opentypeless.android.editor.host;
                                interface InputConnectionRegistry {
                                    android.view.inputmethod.InputConnection resolve(long token);
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    void apply(
                                            InputConnectionRegistry registry,
                                            android.inputmethodservice.InputMethodService service) {
                                        registry.resolve(1L);
                                        service.getCurrentInputConnection();
                                        service.sendDefaultEditorAction(false);
                                        service.sendDownUpKeyEvents(66);
                                        service.sendKeyChar('x');
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDITOR_TRANSACTION_CAPABILITY_TRANSFER");
        result.assertFailureContains("EDITOR_TRANSACTION_INDIRECT_IME_ACCESS");
    }

    @Test
    public void debugAndReleaseTransactionWriteInventoryRejectsAnyNewEdge() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    boolean apply(android.view.inputmethod.InputConnection connection) {
                                        return connection.commitText("safe", 1);
                                    }
                                }
                                """)));

        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));

        result.assertFailureContains("EDITOR_WRITE_BASELINE");
    }

    @Test
    public void transactionManagerMustRemainPackagePrivateFinalAndTopLevel() throws Exception {
        for (String declaration : List.of(
                "public final class EditorTransactionManager",
                "class EditorTransactionManager",
                "abstract class EditorTransactionManager")) {
            Path project = compileProject(
                    Map.ofEntries(
                            source(
                                    "com.opentypeless.android.editor.host.EditorTransactionManager",
                                    """
                                    package com.opentypeless.android.editor.host;
                                    %s {}
                                    """.formatted(declaration))));

            audit(project).assertFailureContains("EDITOR_TRANSACTION_DECLARATION");
        }
    }

    @Test
    public void exactEvidenceReaderMayQueryButNeverMutateInputConnection() throws Exception {
        Path reader = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.EditorEvidenceReader",
                                """
                                package com.opentypeless.android.ime;
                                final class EditorEvidenceReader {
                                    CharSequence read(android.view.inputmethod.InputConnection connection) {
                                        connection.getExtractedText(
                                                new android.view.inputmethod.ExtractedTextRequest(), 0);
                                        connection.getTextBeforeCursor(8, 0);
                                        connection.getTextAfterCursor(8, 0);
                                        return connection.getSelectedText(0);
                                    }
                                }
                                """)));
        audit(reader).assertSuccess();

        for (String body : List.of(
                "connection.commitText(\"x\", 1);",
                "connection.setSelection(1, 1);",
                "connection.finishComposingText();")) {
            Path writer = compileProject(
                    Map.ofEntries(
                            source(
                                    "com.opentypeless.android.ime.EditorEvidenceReader",
                                    """
                                    package com.opentypeless.android.ime;
                                    final class EditorEvidenceReader {
                                        void write(android.view.inputmethod.InputConnection connection) {
                                            %s
                                        }
                                    }
                                    """.formatted(body))));
            GateResult result = audit(writer);
            result.assertFailureContains("EDITOR_WRITE");
            result.assertFailureContains("READ_ONLY_CAPABILITY_MEMBER");
        }

        Path bindingAccessor = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.EditorEvidenceReader",
                                """
                                package com.opentypeless.android.ime;
                                final class EditorEvidenceReader {
                                    Object read(android.view.inputmethod.InputBinding binding) {
                                        return binding.getConnection();
                                    }
                                }
                                """)));
        audit(bindingAccessor).assertFailureContains("READ_ONLY_CAPABILITY_MEMBER");

        Path spoof = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.EditorEvidenceReader$Evil",
                                """
                                package com.opentypeless.android.ime;
                                final class EditorEvidenceReader$Evil {
                                    android.view.inputmethod.InputConnection stolen;
                                }
                                """)));
        audit(spoof).assertFailureContains("EditorEvidenceReader$Evil");
    }

    @Test
    public void dollarNamedTopLevelClassCannotSpoofRegistryNestmate() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.ProcessInputConnectionRegistry$Evil",
                                """
                                package com.opentypeless.android.editor.host;
                                final class ProcessInputConnectionRegistry$Evil {
                                    android.view.inputmethod.InputConnection stolen;
                                }
                                """)));

        audit(project).assertFailureContains("ProcessInputConnectionRegistry$Evil");
    }

    @Test
    public void homeMetadataMayUseOnlyServiceClassLiteral() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.HomeActivity",
                                """
                                package com.opentypeless.android;
                                final class HomeActivity {
                                    Class<?> imeServiceClass() {
                                        return com.opentypeless.android.ime.OpenTypelessImeService.class;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.MainActivity",
                                """
                                package com.opentypeless.android;
                                final class MainActivity {
                                    Class<?> imeServiceClass() {
                                        return com.opentypeless.android.ime.OpenTypelessImeService.class;
                                    }
                                }
                                """)));

        audit(project).assertSuccess();
    }

    @Test
    public void homeServiceFieldIsNotMetadataAndIsRejected() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.HomeActivity",
                                """
                                package com.opentypeless.android;
                                final class HomeActivity {
                                    com.opentypeless.android.ime.OpenTypelessImeService service;
                                }
                                """)));

        audit(project).assertFailureContains("HomeActivity");
    }

    @Test
    public void neutralSelectionInertStringsAndOwnHelperAreAllowed() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.settings.NeutralSettings",
                                """
                                package com.opentypeless.android.settings;
                                final class NeutralSettings {
                                    void select(android.widget.Spinner spinner) { spinner.setSelection(2); }
                                    String inert() {
                                        return "android.view.inputmethod.InputConnection.commitText";
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.settings.OwnHelper",
                                """
                                package com.opentypeless.android.settings;
                                final class OwnHelper {
                                    private void commitText(String diagnosticLabel) {}
                                    void record() { commitText("not an editor write"); }
                                }
                                """)));

        audit(project).assertSuccess();
    }

    @Test
    public void classLoadingReflectionAndMethodHandlesAreRejected() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.provider.Loader",
                                """
                                package com.opentypeless.android.provider;
                                final class Loader extends ClassLoader {
                                    Class<?> load(String name) throws Exception { return loadClass(name); }
                                    Class<?> define(byte[] bytes) {
                                        return defineClass(null, bytes, 0, bytes.length);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.Reflector",
                                """
                                package com.opentypeless.android.provider;
                                import java.lang.reflect.Method;
                                final class Reflector {
                                    Object invoke(String type, Object target) throws Exception {
                                        Class<?> loaded = Class.forName(type);
                                        Method method = loaded.getMethods()[0];
                                        return method.invoke(target);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.HandleLookup",
                                """
                                package com.opentypeless.android.provider;
                                import java.lang.invoke.MethodHandles;
                                final class HandleLookup {
                                    Object lookup() { return MethodHandles.lookup(); }
                                }
                                """)));

        GateResult result = audit(project);

        result.assertFailureContains("Loader");
        result.assertFailureContains("Reflector");
        result.assertFailureContains("HandleLookup");
        result.assertFailureContains("REFLECTION");
    }

    @Test
    public void standardLambdaRecordAndStringConcatBootstrapsAreAllowed() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.model.SafeBootstrapUsers",
                                """
                                package com.opentypeless.android.model;
                                import java.util.function.Supplier;
                                record SafeBootstrapUsers(String value) {
                                    Supplier<String> supplier() { return () -> "safe:" + value; }
                                }
                                """)));

        audit(project).assertSuccess();
    }

    @Test
    public void pureDomainRejectsEveryAndroidAndAndroidxTypeEdgeButAllowsPureJavaRecords()
            throws Exception {
        Path unsafe = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.ClockBackedValue",
                                """
                                package com.opentypeless.android.editor;
                                final class ClockBackedValue {
                                    long now() { return android.os.SystemClock.elapsedRealtime(); }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.ParcelableValue",
                                """
                                package com.opentypeless.android.editor;
                                final class ParcelableValue implements android.os.Parcelable {}
                                """),
                        source(
                                "com.opentypeless.android.editor.ViewModelValue",
                                """
                                package com.opentypeless.android.editor;
                                final class ViewModelValue extends androidx.lifecycle.ViewModel {}
                                """)));

        GateResult unsafeResult = audit(unsafe);
        unsafeResult.assertFailureContains("PURE_DOMAIN_ANDROID_EDGE");
        unsafeResult.assertFailureContains("ClockBackedValue");
        unsafeResult.assertFailureContains("android/os/SystemClock");
        unsafeResult.assertFailureContains("ParcelableValue");
        unsafeResult.assertFailureContains("android/os/Parcelable");
        unsafeResult.assertFailureContains("ViewModelValue");
        unsafeResult.assertFailureContains("androidx/lifecycle/ViewModel");

        Path safe = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.SafeValue",
                                """
                                package com.opentypeless.android.editor;
                                record SafeValue(String text, long revision) {}
                                """)));

        audit(safe).assertSuccess();
    }

    @Test
    public void pureEditorModelsCannotAcquireSerializationContracts() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.SerializableValue",
                                """
                                package com.opentypeless.android.editor;
                                final class SerializableValue implements java.io.Serializable {}
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDITOR_MODEL_SERIALIZATION_EDGE");
        result.assertFailureContains("java/io/Serializable");
    }

    @Test
    public void commitRecordAndReceiptCannotCarryThrowableOrExecutionCapability()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {
                                    private final Throwable failure = null;
                                    private final Runnable retry = null;
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.TransactionReceipt",
                                """
                                package com.opentypeless.android.editor;
                                public interface TransactionReceipt {
                                    java.util.concurrent.Callable<Object> callback();
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("COMMIT_ENVELOPE_THROWABLE");
        result.assertFailureContains("COMMIT_ENVELOPE_EXECUTION_CAPABILITY");
        result.assertFailureContains("java/lang/Runnable");
        result.assertFailureContains("java/util/concurrent/Callable");
    }

    @Test
    public void transactionAuditAndOperationKindRemainExactContentFreeHostValues()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.OperationSource",
                                """
                                package com.opentypeless.android.editor;
                                public enum OperationSource { VOICE }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorOperationKind",
                                """
                                package com.opentypeless.android.editor;
                                public enum EditorOperationKind {
                                    INSERT_TEXT,
                                    ARBITRARY_PAYLOAD
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionAudit",
                                """
                                package com.opentypeless.android.editor;
                                public record EditorTransactionAudit(
                                        OperationSource source,
                                        EditorOperationKind operationKind,
                                        EditorTransactionResult result,
                                        Throwable retainedFailure) {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.BadAuditConsumer",
                                """
                                package com.opentypeless.android.recognition;
                                final class BadAuditConsumer {
                                    com.opentypeless.android.editor.EditorTransactionAudit forge(
                                            com.opentypeless.android.editor.OperationSource source,
                                            com.opentypeless.android.editor.EditorOperationKind kind,
                                            com.opentypeless.android.editor.EditorTransactionResult result) {
                                        return new com.opentypeless.android.editor
                                                .EditorTransactionAudit(
                                                        source, kind, result,
                                                        new RuntimeException("secret"));
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_SHAPE");
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_CONTENT");
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_KIND_SHAPE");
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_SCOPE_TRANSFER");
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_CALLER");
    }

    @Test
    public void transactionAuditConstructionStorageAndSinkCallerAreExact() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.OperationSource",
                                """
                                package com.opentypeless.android.editor;
                                public enum OperationSource { VOICE }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorOperationKind",
                                """
                                package com.opentypeless.android.editor;
                                public enum EditorOperationKind {
                                    SET_COMPOSITION,
                                    COMMIT_COMPOSITION,
                                    INSERT_TEXT,
                                    REPLACE_SELECTION,
                                    REPLACE_LAST_COMMIT,
                                    DELETE_BEFORE_CURSOR,
                                    PERFORM_EDITOR_ACTION
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionAudit",
                                """
                                package com.opentypeless.android.editor;
                                public record EditorTransactionAudit(
                                        OperationSource source,
                                        EditorOperationKind operationKind,
                                        EditorTransactionResult result) {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    interface AuditSink {
                                        void record(com.opentypeless.android.editor
                                                .EditorTransactionAudit audit);
                                    }
                                    private final AuditSink auditSink;
                                    private com.opentypeless.android.editor.EditorTransactionAudit
                                            retained;

                                    EditorTransactionManager(AuditSink auditSink) {
                                        this.auditSink = auditSink;
                                    }

                                    com.opentypeless.android.editor.EditorTransactionAudit forge(
                                            com.opentypeless.android.editor.OperationSource source,
                                            com.opentypeless.android.editor.EditorOperationKind kind,
                                            com.opentypeless.android.editor.EditorTransactionResult result) {
                                        return new com.opentypeless.android.editor
                                                .EditorTransactionAudit(source, kind, result);
                                    }

                                    void publish(com.opentypeless.android.editor
                                            .EditorTransactionAudit audit) {
                                        auditSink.record(audit);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_HOST_BINDING");
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_CALLER");
        result.assertFailureContains("EDITOR_TRANSACTION_AUDIT_SINK_CALLER");
    }

    @Test
    public void transactionAuditConstructorMethodReferenceCannotEscapeExactManager()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.OperationSource",
                                """
                                package com.opentypeless.android.editor;
                                public enum OperationSource { VOICE }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorOperationKind",
                                """
                                package com.opentypeless.android.editor;
                                public enum EditorOperationKind {
                                    SET_COMPOSITION,
                                    COMMIT_COMPOSITION,
                                    INSERT_TEXT,
                                    REPLACE_SELECTION,
                                    REPLACE_LAST_COMMIT,
                                    DELETE_BEFORE_CURSOR,
                                    PERFORM_EDITOR_ACTION
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionAudit",
                                """
                                package com.opentypeless.android.editor;
                                public record EditorTransactionAudit(
                                        OperationSource source,
                                        EditorOperationKind operationKind,
                                        EditorTransactionResult result) {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    interface Factory {
                                        com.opentypeless.android.editor.EditorTransactionAudit make(
                                                com.opentypeless.android.editor.OperationSource source,
                                                com.opentypeless.android.editor.EditorOperationKind kind,
                                                com.opentypeless.android.editor
                                                        .EditorTransactionResult result);
                                    }

                                    Factory forge() {
                                        return com.opentypeless.android.editor
                                                .EditorTransactionAudit::new;
                                    }
                                }
                                """)));

        audit(project).assertFailureContains("EDITOR_TRANSACTION_AUDIT_CALLER");
    }

    @Test
    public void commitApisCannotExposeMutableRecencyLookupsEvenWithErasedReturns()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {}
                                """),
                        source(
                                "com.opentypeless.android.editor.TransactionReceipt",
                                """
                                package com.opentypeless.android.editor;
                                public interface TransactionReceipt {
                                    Object latest();
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    com.opentypeless.android.editor.CommitRecord takeLastCommit() {
                                        return null;
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("COMMIT_RECENCY_LOOKUP_API");
        result.assertFailureContains("latest");
        result.assertFailureContains("takeLastCommit");
    }

    @Test
    public void commitLedgerMustBeOwnerConfinedFixedSingleSlotAndExactIdAddressed()
            throws Exception {
        Path unsafe = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.CommitLedger",
                                """
                                package com.opentypeless.android.editor.host;
                                final class CommitLedger {
                                    private final java.util.Map<String,
                                            com.opentypeless.android.editor.CommitRecord> records =
                                            new java.util.HashMap<>();
                                    private int capacity = 4;
                                    Object peek() { return null; }
                                }
                                """)));

        GateResult unsafeResult = audit(unsafe);
        unsafeResult.assertFailureContains("COMMIT_LEDGER_SINGLE_SLOT");
        unsafeResult.assertFailureContains("COMMIT_LEDGER_OWNER_CONFINEMENT");
        unsafeResult.assertFailureContains("COMMIT_LEDGER_EXACT_ID_API");
        unsafeResult.assertFailureContains("COMMIT_RECENCY_LOOKUP_API");

        Path safe = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {
                                    private final String id;
                                    public CommitRecord(String id) { this.id = id; }
                                    public String commitId() { return id; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.CommitLedger",
                                """
                                package com.opentypeless.android.editor.host;
                                final class CommitLedger {
                                    private final Thread ownerThread;
                                    private com.opentypeless.android.editor.CommitRecord activeRecord;

                                    CommitLedger() { ownerThread = Thread.currentThread(); }

                                    java.util.Optional<com.opentypeless.android.editor.CommitRecord>
                                            resolve(String commitId,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot current) {
                                        requireOwnerThread();
                                        if (activeRecord == null
                                                || !activeRecord.commitId().equals(commitId)) {
                                            return java.util.Optional.empty();
                                        }
                                        return java.util.Optional.of(activeRecord);
                                    }

                                    java.util.Optional<com.opentypeless.android.editor.CommitRecord>
                                            consume(String commitId,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot current) {
                                        requireOwnerThread();
                                        return resolve(commitId, current);
                                    }

                                    private void requireOwnerThread() {
                                        if (Thread.currentThread() != ownerThread) throw new IllegalStateException();
                                    }
                                }
                                """)));

        audit(safe).assertSuccess();
    }

    @Test
    public void receiptScopeAllowsOnlyTheExactOneShotHostSurface() throws Exception {
        Path safe = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.TransactionReceipt",
                                """
                                package com.opentypeless.android.editor;
                                public interface TransactionReceipt {}
                                """),
                        source(
                                "com.opentypeless.android.editor.TargetChangeReason",
                                """
                                package com.opentypeless.android.editor;
                                public enum TargetChangeReason { INVALID }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    @FunctionalInterface
                                    interface ScopedReceiptConnectionUse {
                                        com.opentypeless.android.editor.TransactionReceipt use(
                                                android.view.inputmethod.InputConnection connection);
                                    }
                                    sealed interface ReceiptConnectionUseResult
                                            permits ReceiptConnectionUsed, ReceiptConnectionInvalid {}
                                    record ReceiptConnectionUsed(
                                            com.opentypeless.android.editor.TransactionReceipt receipt)
                                            implements ReceiptConnectionUseResult {}
                                    record ReceiptConnectionInvalid(
                                            com.opentypeless.android.editor.TargetChangeReason reason)
                                            implements ReceiptConnectionUseResult {}
                                    final class HostLease {
                                        ReceiptConnectionUseResult consumeWithCurrentConnectionForReceipt(
                                                ScopedReceiptConnectionUse use) {
                                            return new ReceiptConnectionUsed(use.use(null));
                                        }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    EditorSessionManager.ReceiptConnectionUseResult apply(
                                            EditorSessionManager.HostLease lease,
                                            com.opentypeless.android.editor.TransactionReceipt receipt) {
                                        return lease.consumeWithCurrentConnectionForReceipt(
                                                connection -> receipt);
                                    }
                                }
                                """)));
        audit(safe).assertSuccess();

        Path unsafe = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.TransactionReceipt",
                                """
                                package com.opentypeless.android.editor;
                                public interface TransactionReceipt {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    public interface ReceiptConnectionUseResult {}
                                    public record ReceiptConnectionUsed(
                                            android.view.inputmethod.InputConnection connection)
                                            implements ReceiptConnectionUseResult {}
                                    public record ReceiptConnectionInvalid(String reason)
                                            implements ReceiptConnectionUseResult {}
                                    public interface ScopedReceiptConnectionUse {
                                        com.opentypeless.android.editor.TransactionReceipt use(
                                                android.view.inputmethod.InputConnection connection);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.BadReceiptConsumer",
                                """
                                package com.opentypeless.android.recognition;
                                final class BadReceiptConsumer {
                                    com.opentypeless.android.editor.host.EditorSessionManager
                                            .ScopedReceiptConnectionUse stolen;
                                }
                                """)));

        GateResult unsafeResult = audit(unsafe);
        unsafeResult.assertFailureContains("RECEIPT_SCOPE_CAPABILITY_STORAGE");
        unsafeResult.assertFailureContains("RECEIPT_SCOPE_CAPABILITY_TRANSFER");
    }

    @Test
    public void undoFacadeRejectsDescriptorVisibilityAndCallerDrift() throws Exception {
        Path malformed = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    public Object undoCommit(Object receipt) { return receipt; }
                                }
                                """)));
        audit(malformed).assertFailureContains("UNDO_FACADE_SHAPE");

        Path stolen = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    interface LiveAuthoritySupplier {}
                                    interface UndoEvidenceReader {
                                        UndoEvidenceReadResult read(
                                                android.view.inputmethod.InputConnection connection,
                                                UndoEvidenceRequest request);
                                    }
                                    record UndoEvidenceRequest(
                                            int beforeUtf16Units, int afterUtf16Units) {}
                                    interface UndoEvidenceReadResult {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    com.opentypeless.android.editor.EditorTransactionResult undoCommit(
                                            String id,
                                            com.opentypeless.android.editor.EditorSessionSnapshot current,
                                            EditorSessionManager.LiveAuthoritySupplier authority,
                                            EditorSessionManager.UndoEvidenceReader reader) {
                                        return null;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.UndoShortcut",
                                """
                                package com.opentypeless.android.editor.host;
                                final class UndoShortcut {
                                    Object steal(
                                            EditorTransactionManager transactions,
                                            com.opentypeless.android.editor.EditorSessionSnapshot current,
                                            EditorSessionManager.LiveAuthoritySupplier authority,
                                            EditorSessionManager.UndoEvidenceReader reader) {
                                        return transactions.undoCommit(
                                                "forged", current, authority, reader);
                                    }
                                }
                                """)));
        audit(stolen).assertFailureContains("UNDO_FACADE_CALLER");
    }

    @Test
    public void replaceLastCommitAndLedgerLookupAreExactTransactionUndoAuthorities()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation {
                                    final class ReplaceLastCommit implements EditorOperation {
                                        public ReplaceLastCommit() {}
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.CommitLedger",
                                """
                                package com.opentypeless.android.editor.host;
                                final class CommitLedger {
                                    java.util.Optional<com.opentypeless.android.editor.CommitRecord>
                                            resolve(String id,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot current) {
                                        return java.util.Optional.empty();
                                    }
                                    java.util.Optional<com.opentypeless.android.editor.CommitRecord>
                                            consume(String id,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot current) {
                                        return java.util.Optional.empty();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    Object apply() {
                                        return new com.opentypeless.android.editor.EditorOperation
                                                .ReplaceLastCommit();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.UndoShortcut",
                                """
                                package com.opentypeless.android.editor.host;
                                final class UndoShortcut {
                                    Object steal(
                                            CommitLedger ledger,
                                            com.opentypeless.android.editor.EditorSessionSnapshot current) {
                                        return ledger.resolve("forged", current);
                                    }
                                    Object consume(
                                            CommitLedger ledger,
                                            com.opentypeless.android.editor.EditorSessionSnapshot current) {
                                        return ledger.consume("forged", current);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("UNDO_OPERATION_AUTHORITY");
        result.assertFailureContains("COMMIT_LEDGER_CALLER");
        result.assertFailureContains("consume");
    }

    @Test
    public void undoEvidenceScopeCannotLeakConnectionPlaintextOrReaderInvocation()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.TextRange",
                                """
                                package com.opentypeless.android.editor;
                                public final class TextRange {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    public interface UndoEvidenceReader {
                                        UndoEvidenceReadResult read(
                                                android.view.inputmethod.InputConnection connection,
                                                UndoEvidenceRequest request);
                                    }
                                    public record UndoEvidenceRequest(
                                            int beforeUtf16Units,
                                            int afterUtf16Units,
                                            android.view.inputmethod.InputConnection retained) {}
                                    public interface UndoEvidenceReadResult {}
                                    public record UndoEvidence(
                                            boolean selectionAvailable,
                                            int selectionStart,
                                            int selectionEnd,
                                            boolean selectedTextAvailable,
                                            CharSequence selectedText,
                                            boolean beforeTextAvailable,
                                            CharSequence beforeText,
                                            boolean afterTextAvailable,
                                            CharSequence afterText)
                                            implements UndoEvidenceReadResult {
                                        @Override public String toString() {
                                            return selectedText.toString();
                                        }
                                    }
                                    public record UndoEvidenceUnavailable()
                                            implements UndoEvidenceReadResult {}

                                    private record MaterializedUndoEvidence() {}

                                    MaterializedUndoEvidence readUndoEvidence(
                                            UndoEvidenceReader reader,
                                            android.view.inputmethod.InputConnection connection,
                                            com.opentypeless.android.editor.TextRange selection,
                                            UndoEvidenceRequest request) {
                                        reader.read(connection, request);
                                        return null;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.BadUndoEvidenceConsumer",
                                """
                                package com.opentypeless.android.recognition;
                                final class BadUndoEvidenceConsumer {
                                    com.opentypeless.android.editor.host.EditorSessionManager
                                            .UndoEvidenceReader reader;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("UNDO_EVIDENCE_SCOPE_SHAPE");
        result.assertFailureContains("UNDO_EVIDENCE_CAPABILITY_STORAGE");
        result.assertFailureContains("UNDO_EVIDENCE_REDACTION");
        result.assertFailureContains("UNDO_EVIDENCE_SCOPE_TRANSFER");
        result.assertFailureContains("UNDO_EVIDENCE_CALLER");
    }

    @Test
    public void rawRestoreFacadeCallerAndOrdinarySourceClaimFailClosed() throws Exception {
        Path malformed = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    public Object restoreRawCommit(Object receipt) {
                                        return receipt;
                                    }
                                }
                                """)));
        GateResult malformedResult = audit(malformed);
        malformedResult.assertFailureContains("RAW_RESTORE_FACADE_SHAPE");
        malformedResult.assertFailureContains("RAW_RESTORE_APPLY_DENIAL");

        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {}
                                """),
                        source(
                                "com.opentypeless.android.editor.OperationSource",
                                """
                                package com.opentypeless.android.editor;
                                public enum OperationSource { VOICE, RAW_RESTORE }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation { OperationSource source(); }
                                """),
                        source(
                                "com.opentypeless.android.editor.RejectionReason",
                                """
                                package com.opentypeless.android.editor;
                                public enum RejectionReason { OPERATION_NOT_SUPPORTED }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    interface LiveAuthoritySupplier {}
                                    interface UndoEvidenceReader {
                                        UndoEvidenceReadResult read(
                                                android.view.inputmethod.InputConnection connection,
                                                UndoEvidenceRequest request);
                                    }
                                    record UndoEvidenceRequest(
                                            int beforeUtf16Units, int afterUtf16Units) {}
                                    interface UndoEvidenceReadResult {}
                                    static final class Validated {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    com.opentypeless.android.editor.EditorTransactionResult
                                            restoreRawCommit(
                                                    String id,
                                                    com.opentypeless.android.editor
                                                            .EditorSessionSnapshot current,
                                                    EditorSessionManager.LiveAuthoritySupplier authority,
                                                    EditorSessionManager.UndoEvidenceReader reader) {
                                        return null;
                                    }
                                    private com.opentypeless.android.editor.RejectionReason
                                            policyRejection(
                                                    com.opentypeless.android.editor
                                                            .EditorSessionSnapshot current,
                                                    com.opentypeless.android.editor.EditorOperation operation,
                                                    EditorSessionManager.Validated validated,
                                                    boolean evidence) {
                                        return null;
                                    }
                                    Object apply() {
                                        return com.opentypeless.android.editor.OperationSource
                                                .RAW_RESTORE;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.RawRestoreShortcut",
                                """
                                package com.opentypeless.android.editor.host;
                                final class RawRestoreShortcut {
                                    Object steal(
                                            EditorTransactionManager transactions,
                                            com.opentypeless.android.editor.EditorSessionSnapshot current,
                                            EditorSessionManager.LiveAuthoritySupplier authority,
                                            EditorSessionManager.UndoEvidenceReader reader) {
                                        return transactions.restoreRawCommit(
                                                "forged", current, authority, reader);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("RAW_RESTORE_FACADE_CALLER");
        result.assertFailureContains("RAW_RESTORE_APPLY_DENIAL");
        result.assertFailureContains("RAW_RESTORE_OPERATION_AUTHORITY");
    }

    @Test
    public void rawTransitionCannotLeakConnectionForgeStateOrMoveToAnotherCaller()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    public enum RawProofState { COMMITTED, ORIGINAL, UNDO, RAW, FORGED }
                                    public final class RawTransition {
                                        android.view.inputmethod.InputConnection retained;
                                        @Override public String toString() {
                                            return retained.toString();
                                        }
                                    }
                                    RawTransition prepareRawTransition(
                                            com.opentypeless.android.editor.CommitRecord record,
                                            RawProofState fromState,
                                            RawProofState targetState) {
                                        return null;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.RawTransitionShortcut",
                                """
                                package com.opentypeless.android.editor.host;
                                final class RawTransitionShortcut {
                                    Object steal(
                                            EditorSessionManager sessions,
                                            com.opentypeless.android.editor.CommitRecord record) {
                                        return sessions.prepareRawTransition(
                                                record,
                                                EditorSessionManager.RawProofState.COMMITTED,
                                                EditorSessionManager.RawProofState.ORIGINAL);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    Object undoEvil(
                                            EditorSessionManager sessions,
                                            com.opentypeless.android.editor.CommitRecord record) {
                                        return sessions.prepareRawTransition(
                                                record,
                                                EditorSessionManager.RawProofState.COMMITTED,
                                                EditorSessionManager.RawProofState.UNDO);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.BadRawTransitionConsumer",
                                """
                                package com.opentypeless.android.recognition;
                                final class BadRawTransitionConsumer {
                                    com.opentypeless.android.editor.host.EditorSessionManager
                                            .RawTransition retained;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("RAW_TRANSITION_SCOPE_SHAPE");
        result.assertFailureContains("RAW_TRANSITION_CAPABILITY_STORAGE");
        result.assertFailureContains("RAW_TRANSITION_REDACTION");
        result.assertFailureContains("RAW_RESTORE_SCOPE_TRANSFER");
        result.assertFailureContains("RAW_TRANSITION_CALLER");
        result.assertFailureContains("RAW_TRANSITION_OWNER_BINDING");
    }

    @Test
    public void rolledBackCannotBeConstructedOutsideTheVerifiedRestoreHelper()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.TransactionFailure",
                                """
                                package com.opentypeless.android.editor;
                                public final class TransactionFailure {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {
                                    record RolledBack(TransactionFailure originalFailure)
                                            implements EditorTransactionResult {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    Object claimWithoutRestoring(
                                            com.opentypeless.android.editor.TransactionFailure failure) {
                                        return new com.opentypeless.android.editor
                                                .EditorTransactionResult.RolledBack(failure);
                                    }
                                }
                                """)));

        audit(project).assertFailureContains("EDT013_ROLLED_BACK_CALLER");
    }

    @Test
    public void replaceEvidenceAndTransitionRejectShapeLeakAndWrongCaller() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation {
                                    final class ReplaceSelection implements EditorOperation {
                                        public String text() { return "x"; }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    public interface CurrentEvidenceReader {
                                        Object read(
                                                android.view.inputmethod.InputConnection connection,
                                                CurrentEvidenceRequest request);
                                    }
                                    public record CurrentEvidenceRequest(int before) {}
                                    public interface EvidenceReadResult {}
                                    public record CurrentEvidence(String secret)
                                            implements EvidenceReadResult {
                                        @Override public String toString() { return secret; }
                                    }
                                    public enum ReplaceProofState {
                                        ORIGINAL, INTENDED, FORGED
                                    }
                                    public static final class ReplaceTransition {
                                        android.view.inputmethod.InputConnection retained;
                                        String secret;
                                    }
                                    public ReplaceTransition prepareReplaceTransition(
                                            com.opentypeless.android.editor.EditorSessionSnapshot expected,
                                            com.opentypeless.android.editor.EditorOperation.ReplaceSelection operation,
                                            ReplaceProofState state) {
                                        return null;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    Object steal(
                                            EditorSessionManager sessions,
                                            com.opentypeless.android.editor.EditorSessionSnapshot expected,
                                            com.opentypeless.android.editor.EditorOperation.ReplaceSelection operation) {
                                        return sessions.prepareReplaceTransition(
                                                expected,
                                                operation,
                                                EditorSessionManager.ReplaceProofState.INTENDED);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.CurrentEvidenceShortcut",
                                """
                                package com.opentypeless.android.editor.host;
                                final class CurrentEvidenceShortcut {
                                    Object steal(
                                            EditorSessionManager.CurrentEvidenceReader reader,
                                            android.view.inputmethod.InputConnection connection,
                                            EditorSessionManager.CurrentEvidenceRequest request) {
                                        return reader.read(connection, request);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.BadReplaceEvidenceConsumer",
                                """
                                package com.opentypeless.android.recognition;
                                final class BadReplaceEvidenceConsumer {
                                    com.opentypeless.android.editor.host.EditorSessionManager
                                            .CurrentEvidenceReader reader;
                                    com.opentypeless.android.editor.host.EditorSessionManager
                                            .ReplaceTransition token;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REPLACE_SELECTION_MODEL_SHAPE");
        result.assertFailureContains("CURRENT_EVIDENCE_SCOPE_SHAPE");
        result.assertFailureContains("CURRENT_EVIDENCE_REDACTION");
        result.assertFailureContains("CURRENT_EVIDENCE_CALLER");
        result.assertFailureContains("REPLACE_TRANSITION_SCOPE_SHAPE");
        result.assertFailureContains("REPLACE_TRANSITION_CAPABILITY_STORAGE");
        result.assertFailureContains("REPLACE_TRANSITION_CALLER");
        result.assertFailureContains("REPLACE_TRANSITION_OWNER_BINDING");
        result.assertFailureContains("REPLACE_EVIDENCE_SCOPE_TRANSFER");
    }

    @Test
    public void replaceDispatcherCannotAddDeleteSecondCommitOrSetSelection() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.TextRange",
                                """
                                package com.opentypeless.android.editor;
                                public record TextRange(int start, int end) {}
                                """),
                        source(
                                "com.opentypeless.android.editor.TextFingerprint",
                                """
                                package com.opentypeless.android.editor;
                                public final class TextFingerprint {}
                                """),
                        source(
                                "com.opentypeless.android.editor.OperationSource",
                                """
                                package com.opentypeless.android.editor;
                                public enum OperationSource { VOICE }
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorOperation",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorOperation {
                                    record ReplaceSelection(
                                            TextRange expectedSelection,
                                            TextFingerprint expectedTextHash,
                                            String text,
                                            OperationSource source)
                                            implements EditorOperation {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {
                                    static boolean invokeMutator(
                                            android.view.inputmethod.InputConnection connection,
                                            com.opentypeless.android.editor.EditorOperation operation) {
                                        if (operation instanceof com.opentypeless.android.editor
                                                .EditorOperation.ReplaceSelection replace) {
                                            connection.deleteSurroundingTextInCodePoints(1, 0);
                                            connection.setSelection(0, 0);
                                            return connection.commitText(replace.text(), 1);
                                        }
                                        return true;
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REPLACE_SELECTION_DISPATCHER");
        result.assertFailureContains("EDITOR_TRANSACTION_WRITE_SURFACE");
    }

    @Test
    public void voiceTransactionFacadeSessionAndFlagRejectLeakWrongCallerAndUnsafeDefault()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {}
                                """),
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {
                                    public interface RawTranscript {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.TransactionReceipt",
                                """
                                package com.opentypeless.android.editor;
                                public interface TransactionReceipt {}
                                """),
                        source(
                                "com.opentypeless.android.editor.CompositionCoordinator",
                                """
                                package com.opentypeless.android.editor;
                                public final class CompositionCoordinator {
                                    public static final class Observation {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {
                                    public interface KeyboardHost {
                                        android.view.inputmethod.EditorInfo currentEditorInfo();
                                        android.view.inputmethod.InputConnection currentInputConnection();
                                    }
                                    public com.opentypeless.android.editor.EditorTransactionResult
                                            setVoiceComposition(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                    String text,
                                                    long revision) { return null; }
                                    public com.opentypeless.android.editor.TransactionReceipt
                                            commitVoiceComposition(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                    long revision,
                                                    com.opentypeless.android.editor.CommitRecord.RawTranscript raw) {
                                        return null;
                                    }
                                    public com.opentypeless.android.editor.EditorTransactionResult
                                            finishVoiceComposition(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                    long revision) { return null; }
                                    public com.opentypeless.android.editor.TransactionReceipt
                                            commitVoiceText(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                    String text,
                                                    com.opentypeless.android.editor.CommitRecord.RawTranscript raw) {
                                        return null;
                                    }
                                    public com.opentypeless.android.editor.EditorTransactionResult
                                            undoVoiceCommit(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                    String id) { return null; }
                                    public com.opentypeless.android.editor.EditorTransactionResult
                                            restoreRawVoiceCommit(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                    String id) { return null; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.BadVoiceFacadeCaller",
                                """
                                package com.opentypeless.android.recognition;
                                final class BadVoiceFacadeCaller {
                                    Object call(
                                            com.opentypeless.android.editor.host.EditorSessionManager manager,
                                            com.opentypeless.android.editor.host.EditorSessionManager.KeyboardHost host,
                                            com.opentypeless.android.editor.EditorSessionSnapshot snapshot) {
                                        return manager.setVoiceComposition(host, snapshot, "secret", 1L);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.OpenTypelessImeService",
                                """
                                package com.opentypeless.android.ime;
                                public final class OpenTypelessImeService {
                                    public static class VoiceTransactionSession {
                                        static final class KeyboardPreemption {
                                            String secret = "must-not-be-retained";
                                        }
                                        android.view.inputmethod.InputConnection retained;
                                        com.opentypeless.android.editor.CommitRecord record;
                                        public VoiceTransactionSession(
                                                long generation,
                                                com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                com.opentypeless.android.editor.CompositionCoordinator coordinator,
                                                com.opentypeless.android.editor.CompositionCoordinator.Observation observation) {}
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.speech.runtime.VoiceEditorTransactionConfig",
                                """
                                package com.opentypeless.android.speech.runtime;
                                public final class VoiceEditorTransactionConfig {
                                    private static final String FILE = "voice";
                                    private static final String ENABLED = "enabled";
                                    public VoiceEditorTransactionConfig() {}
                                    public static boolean enabled(android.content.Context context) {
                                        return false;
                                    }
                                    public static void setEnabled(
                                            android.content.Context context, boolean enabled) {}
                                    private static Object preferences(android.content.Context context) {
                                        return null;
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("EDT017_VOICE_FACADE_CALLER");
        result.assertFailureContains("EDT017_VOICE_TRANSACTION_EDGE");
        result.assertFailureContains("EDT017_VOICE_SESSION_SHAPE");
        result.assertFailureContains("EDT017_FEATURE_FLAG_SHAPE");
        result.assertFailureContains("VOC011_FEATURE_FLAG_SHAPE");
        result.assertFailureContains("CMP004_COORDINATOR_OWNER");
        result.assertFailureContains("CMP004_VOICE_COORDINATOR_EDGE");
        result.assertFailureContains("CMP005_KEYBOARD_PREEMPTION_SHAPE");
    }

    @Test
    public void voiceLifecycleBoundaryCannotStopAndAwaitABackgroundFinal() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "android.content.Intent",
                                """
                                package android.content;
                                public final class Intent {
                                    public static final String ACTION_SCREEN_OFF =
                                            "android.intent.action.SCREEN_OFF";
                                    public String getAction() { return ACTION_SCREEN_OFF; }
                                }
                                """),
                        source(
                                "android.content.BroadcastReceiver",
                                """
                                package android.content;
                                public abstract class BroadcastReceiver {
                                    public abstract void onReceive(Context context, Intent intent);
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoiceController",
                                """
                                package com.opentypeless.android.ime;
                                public interface VoiceController {
                                    void stop();
                                    void cancel();
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.OpenTypelessImeService",
                                """
                                package com.opentypeless.android.ime;
                                public final class OpenTypelessImeService {
                                    private android.content.BroadcastReceiver screenOffReceiver =
                                            createScreenOffReceiver(this::cancelVoiceForLifecycle);
                                    private boolean screenOffReceiverRegistered;
                                    private VoiceController voiceController;

                                    static android.content.BroadcastReceiver createScreenOffReceiver(
                                            Runnable cancellation) {
                                        return new android.content.BroadcastReceiver() {
                                            @Override
                                            public void onReceive(
                                                    android.content.Context context,
                                                    android.content.Intent intent) {
                                                intent.getAction();
                                                cancellation.run();
                                            }
                                        };
                                    }

                                    static void cancelControllerForLifecycle(
                                            VoiceController controller) {
                                        controller.stop();
                                    }

                                    private void cancelVoiceForLifecycle() {
                                        cancelControllerForLifecycle(voiceController);
                                    }

                                    private void registerScreenOffReceiver() {}
                                    private void unregisterScreenOffReceiver() {}
                                    public void onStartInput(
                                            android.view.inputmethod.EditorInfo info,
                                            boolean restarting) {}
                                    public void onFinishInput() {}
                                    public void onFinishInputView(boolean finishingInput) {}
                                    public void onWindowHidden() {}
                                    public void onDestroy() {}
                                    public void onCreate() {}
                                }
                                """)));

        GateResult shape = audit(project);
        shape.assertFailureContains("CMP006_LIFECYCLE_SHAPE");

        GateResult edges = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        edges.assertFailureContains("CMP006_EXACT_EDGE");
    }

    @Test
    public void voiceWriterBranchesCannotRunTransactionAndLegacyPathsTogether()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.ime.TranscriptUpdate",
                                """
                                package com.opentypeless.android.ime;
                                public final class TranscriptUpdate {}
                                """),
                        source(
                                "com.opentypeless.android.ime.DictationResult",
                                """
                                package com.opentypeless.android.ime;
                                public final class DictationResult {}
                                """),
                        source(
                                "com.opentypeless.android.ime.DictationRequest",
                                """
                                package com.opentypeless.android.ime;
                                public final class DictationRequest {
                                    public enum CaptureMode { DEFAULT }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoiceCompositionSession",
                                """
                                package com.opentypeless.android.ime;
                                public final class VoiceCompositionSession {
                                    public VoiceCompositionSession() {}
                                    public void apply() {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.OpenTypelessImeService",
                                """
                                package com.opentypeless.android.ime;
                                public final class OpenTypelessImeService {
                                    static final class CommitTarget {}
                                    static final class LastVoiceCommit {}
                                    static final class VoiceTransactionSession {
                                        VoiceTransactionSession(
                                                long generation,
                                                com.opentypeless.android.editor.EditorSessionSnapshot snapshot) {}
                                    }
                                    void applyTranscriptUpdate(
                                            CommitTarget target, TranscriptUpdate update) {
                                        applyVoiceTransactionUpdate(target, update);
                                        applySpeechCoreProjection();
                                    }
                                    void commitResult(CommitTarget target, DictationResult result) {
                                        commitVoiceTransactionResult(target, result);
                                        commitSpeechCoreV2Result();
                                    }
                                    void toggleRecording(DictationRequest.CaptureMode mode) {
                                        new VoiceTransactionSession(
                                                1L,
                                                new com.opentypeless.android.editor
                                                        .EditorSessionSnapshot());
                                        new VoiceCompositionSession();
                                    }
                                    void applyVoiceTransactionUpdate(
                                            CommitTarget target, TranscriptUpdate update) {}
                                    void applySpeechCoreProjection() {}
                                    void commitVoiceTransactionResult(
                                            CommitTarget target, DictationResult result) {}
                                    void commitSpeechCoreV2Result() {}
                                }
                                """)));

        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("EDT017_WRITER_MUTUAL_EXCLUSION");
        result.assertFailureContains("EDT017_EXACT_EDGE");
    }

    @Test
    public void voiceCoordinatorCannotLeakOrBeCalledOutsideTheExactBoundSession()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.CompositionCoordinator",
                                """
                                package com.opentypeless.android.editor;
                                public final class CompositionCoordinator {
                                    public static final class Observation {}
                                    public Observation observe() { return new Observation(); }
                                    public Object cancel(Observation expected) { return null; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.CoordinatorLeak",
                                """
                                package com.opentypeless.android.recognition;
                                final class CoordinatorLeak {
                                    private final com.opentypeless.android.editor.CompositionCoordinator
                                            coordinator = new com.opentypeless.android.editor.CompositionCoordinator();
                                    private com.opentypeless.android.editor.CompositionCoordinator.Observation
                                            observation = coordinator.observe();
                                    Object release() { return coordinator.cancel(observation); }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CMP004_COORDINATOR_SCOPE_TRANSFER");
        result.assertFailureContains("CMP004_COORDINATOR_CALLER");
    }

    @Test
    public void voiceControllerRejectsCapabilityShapeAdapterDriftAndPipelineBypass()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.database.sqlite.SQLiteDatabase",
                                """
                                package android.database.sqlite;
                                public final class SQLiteDatabase {}
                                """),
                        source(
                                "com.opentypeless.android.diagnostics.RecognitionRoute",
                                """
                                package com.opentypeless.android.diagnostics;
                                public final class RecognitionRoute {}
                                """),
                        source(
                                "com.opentypeless.android.ime.DictationRequest",
                                """
                                package com.opentypeless.android.ime;
                                public final class DictationRequest {}
                                """),
                        source(
                                "com.opentypeless.android.ime.TranscriptUpdate",
                                """
                                package com.opentypeless.android.ime;
                                public final class TranscriptUpdate {}
                                """),
                        source(
                                "com.opentypeless.android.ime.DictationResult",
                                """
                                package com.opentypeless.android.ime;
                                public final class DictationResult {}
                                """),
                        source(
                                "com.opentypeless.android.ime.VoiceController",
                                """
                                package com.opentypeless.android.ime;
                                public interface VoiceController {
                                    enum State { IDLE, RECORDING, TRANSCRIBING, POLISHING }
                                    interface Events {
                                        void onState(State state, String message);
                                        default void onRoute(
                                                com.opentypeless.android.diagnostics.RecognitionRoute route) {}
                                        default void onReadyForSpeech() {}
                                        default void onBeginningOfSpeech() {}
                                        default void onTranscript(TranscriptUpdate update) {}
                                        void onResult(DictationResult result);
                                        void onError(String message);
                                    }
                                    boolean start(DictationRequest request, Events events);
                                    void stop();
                                    void cancel();
                                    State state();
                                    android.database.sqlite.SQLiteDatabase database();
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoicePipeline",
                                """
                                package com.opentypeless.android.ime;
                                public final class VoicePipeline {
                                    public enum State { IDLE, RECORDING, TRANSCRIBING, POLISHING }
                                    public interface Listener {}
                                    public boolean start(DictationRequest request, Listener listener) {
                                        return true;
                                    }
                                    public void stopRecording() {}
                                    public void cancel() {}
                                    public State state() { return State.IDLE; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoicePipelineAdapter",
                                """
                                package com.opentypeless.android.ime;
                                public final class VoicePipelineAdapter implements VoiceController {
                                    private final VoicePipeline pipeline;
                                    private Object leakedUi;
                                    public VoicePipelineAdapter(VoicePipeline pipeline) {
                                        this.pipeline = pipeline;
                                    }
                                    public boolean start(DictationRequest request, Events events) {
                                        return false;
                                    }
                                    public void stop() { pipeline.stopRecording(); }
                                    public void cancel() { pipeline.cancel(); }
                                    public State state() { return State.IDLE; }
                                    public android.database.sqlite.SQLiteDatabase database() {
                                        return null;
                                    }
                                    static VoicePipeline.Listener listenerFor(Events events) {
                                        return null;
                                    }
                                    static State controllerState(VoicePipeline.State state) {
                                        return State.IDLE;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.VoicePipelineBypass",
                                """
                                package com.opentypeless.android.recognition;
                                public final class VoicePipelineBypass {
                                    public boolean start(
                                            com.opentypeless.android.ime.VoicePipeline pipeline,
                                            com.opentypeless.android.ime.DictationRequest request) {
                                        return pipeline.start(request, null);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("VOC001_CONTROLLER_SHAPE");
        result.assertFailureContains("VOC001_PIPELINE_ADAPTER_SHAPE");
        result.assertFailureContains("VOC001_PIPELINE_BYPASS");
    }

    @Test
    public void productionVoiceControllerBinariesAndAdapterEdgesFailClosedWhenAbsent()
            throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("VOC001_BINARY_MISSING");
        result.assertFailureContains("VOC001_EXACT_EDGE");
    }

    @Test
    public void audioCaptureRejectsShapeLeakRawBypassAndUnownedSession() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "android.view.inputmethod.InputConnection",
                                """
                                package android.view.inputmethod;
                                public interface InputConnection {}
                                """),
                        source(
                                "com.opentypeless.android.audio.RecordedAudio",
                                """
                                package com.opentypeless.android.audio;
                                public final class RecordedAudio {}
                                """),
                        source(
                                "com.opentypeless.android.audio.StreamingAudioResult",
                                """
                                package com.opentypeless.android.audio;
                                public final class StreamingAudioResult {}
                                """),
                        source(
                                "com.opentypeless.android.audio.RecordingSession",
                                """
                                package com.opentypeless.android.audio;
                                public final class RecordingSession {
                                    public RecordingSession(boolean manual) {}
                                    public boolean userControlledEndpointing() { return false; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.audio.AudioRecorder",
                                """
                                package com.opentypeless.android.audio;
                                public final class AudioRecorder {
                                    public interface CaptureListener {}
                                    public interface FrameConsumer {}
                                    public AudioRecorder() {}
                                    public void setAttributionContext(android.content.Context value) {}
                                    public RecordedAudio record(
                                            RecordingSession session, int seconds,
                                            CaptureListener listener) { return null; }
                                    public StreamingAudioResult stream(
                                            RecordingSession session, int seconds,
                                            CaptureListener listener, FrameConsumer consumer) {
                                        return null;
                                    }
                                    public void stop(RecordingSession session) {}
                                    public void cancel(RecordingSession session) {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.audio.AudioCapture",
                                """
                                package com.opentypeless.android.audio;
                                public interface AudioCapture {
                                    int SAMPLE_RATE = 16000;
                                    interface Session {
                                        boolean userControlledEndpointing();
                                        boolean active();
                                    }
                                    interface CaptureListener {
                                        default void onReady() {}
                                        default void onBeginningOfSpeech() {}
                                        default void onAudio(byte[] bytes, int length) {}
                                    }
                                    interface FrameConsumer {
                                        void onPcm16Frame(byte[] bytes, int offset, int length);
                                    }
                                    void setAttributionContext(android.content.Context context);
                                    Session createSession(boolean manual);
                                    RecordedAudio record(Session session, int seconds,
                                            CaptureListener listener);
                                    StreamingAudioResult stream(Session session, int seconds,
                                            CaptureListener listener, FrameConsumer consumer);
                                    void stop(Session session);
                                    void cancel(Session session);
                                    android.view.inputmethod.InputConnection leak();
                                }
                                """),
                        source(
                                "com.opentypeless.android.audio.AndroidAudioCapture",
                                """
                                package com.opentypeless.android.audio;
                                public final class AndroidAudioCapture implements AudioCapture {
                                    private final AudioRecorder recorder = new AudioRecorder();
                                    private final String retained = "leak";
                                    public AndroidAudioCapture() {}
                                    public void setAttributionContext(android.content.Context value) {}
                                    public Session createSession(boolean manual) {
                                        return new RecorderSession(this, new RecordingSession(manual));
                                    }
                                    public RecordedAudio record(
                                            Session session, int seconds, CaptureListener listener) {
                                        return null;
                                    }
                                    public StreamingAudioResult stream(
                                            Session session, int seconds, CaptureListener listener,
                                            FrameConsumer consumer) { return null; }
                                    public void stop(Session session) {}
                                    public void cancel(Session session) {}
                                    public android.view.inputmethod.InputConnection leak() { return null; }
                                    private static final class RecorderSession implements Session {
                                        private final AndroidAudioCapture owner;
                                        private final RecordingSession delegate;
                                        private final String retained = "leak";
                                        RecorderSession(
                                                AndroidAudioCapture owner,
                                                RecordingSession delegate) {
                                            this.owner = owner;
                                            this.delegate = delegate;
                                        }
                                        public boolean userControlledEndpointing() { return false; }
                                        public boolean active() { return true; }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.AudioCaptureLeak",
                                """
                                package com.opentypeless.android.provider;
                                public final class AudioCaptureLeak {
                                    public com.opentypeless.android.audio.AudioCapture capture;
                                    public com.opentypeless.android.audio.AudioRecorder recorder;
                                    public com.opentypeless.android.audio.RecordingSession session;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("VOC002_CAPTURE_SHAPE");
        result.assertFailureContains("VOC002_SESSION_SHAPE");
        result.assertFailureContains("VOC002_CAPTURE_ADAPTER_SHAPE");
        result.assertFailureContains("VOC002_SESSION_OWNER_BINDING");
        result.assertFailureContains("VOC002_CAPTURE_SCOPE_TRANSFER");
        result.assertFailureContains("VOC002_RAW_CAPTURE_BYPASS");
        result.assertFailureContains("VOC002_RAW_CAPTURE_SHAPE");
    }

    @Test
    public void productionAudioCaptureBinariesAndExactEdgesFailClosedWhenAbsent()
            throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("VOC002_BINARY_MISSING");
        result.assertFailureContains("VOC002_EXACT_EDGE");
    }

    @Test
    public void voicePipelineFacadeRejectsBloatPublicRuntimeAndRuntimeLeak()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.VoicePipelineRuntime",
                                """
                                package com.opentypeless.android.ime;
                                public final class VoicePipelineRuntime {
                                    public VoicePipelineRuntime(Object context) {}
                                    public void cancel() {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoicePipeline",
                                """
                                package com.opentypeless.android.ime;
                                public final class VoicePipeline {
                                    private final VoicePipelineRuntime runtime;
                                    private final Object extra = new Object();
                                    public VoicePipeline(Object context) {
                                        runtime = new VoicePipelineRuntime(context);
                                    }
                                    public void cancel() {
                                        runtime.cancel();
                                        runtime.cancel();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.VoiceRuntimeLeak",
                                """
                                package com.opentypeless.android.provider;
                                public final class VoiceRuntimeLeak {
                                    public com.opentypeless.android.ime.VoicePipelineRuntime runtime;
                                }
                                """)));
        GateResult result = audit(project);
        result.assertFailureContains("VOC007_FACADE_SHAPE");
        result.assertFailureContains("VOC007_RUNTIME_SHAPE");
        result.assertFailureContains("VOC007_RUNTIME_SCOPE_TRANSFER");
    }

    @Test
    public void productionVoicePipelineFacadeBinariesAndExactEdgesFailClosedWhenAbsent()
            throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("VOC007_BINARY_MISSING");
        result.assertFailureContains("VOC007_EXACT_EDGE");
        result.assertFailureContains("VOC008_BINARY_MISSING");
        result.assertFailureContains("VOC008_EXACT_EDGE");
    }

    @Test
    public void teachBoundaryRejectsCopiedPlaintextWeakResolverAndForeignFactoryCaller()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "android.content.Intent",
                                """
                                package android.content;
                                public class Intent {
                                    public Intent putExtra(String key, String value) { return this; }
                                }
                                """),
                        source(
                                "android.view.View",
                                """
                                package android.view;
                                public class View {}
                                """),
                        source(
                                "com.opentypeless.android.editor.OperationSource",
                                """
                                package com.opentypeless.android.editor;
                                public enum OperationSource { VOICE, LATIN }
                                """),
                        source(
                                "com.opentypeless.android.editor.CommitRecord",
                                """
                                package com.opentypeless.android.editor;
                                public final class CommitRecord {
                                    public interface RawTranscript {
                                        final class Present implements RawTranscript {}
                                    }
                                    public OperationSource source() { return OperationSource.VOICE; }
                                    public boolean learningAllowed() { return true; }
                                    public RawTranscript rawTranscript() { return new RawTranscript.Present(); }
                                    public String insertedText() { return ""; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.data.HistoryEntry",
                                """
                                package com.opentypeless.android.data;
                                public final class HistoryEntry {}
                                """),
                        source(
                                "com.opentypeless.android.personalization.TeachCorrectionResolver",
                                """
                                package com.opentypeless.android.personalization;
                                public final class TeachCorrectionResolver {
                                    public static boolean isEligible(
                                            com.opentypeless.android.editor.CommitRecord record) {
                                        return record != null;
                                    }
                                    public static com.opentypeless.android.data.HistoryEntry resolve(
                                            com.opentypeless.android.data.HistoryEntry stored,
                                            com.opentypeless.android.editor.CommitRecord record) {
                                        return stored;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.HistoryActivity",
                                """
                                package com.opentypeless.android;
                                public final class HistoryActivity {
                                    public static android.content.Intent createTeachIntent(
                                            android.content.Context context,
                                            com.opentypeless.android.editor.CommitRecord record,
                                            long historyId) {
                                        return new android.content.Intent();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.OpenTypelessImeService",
                                """
                                package com.opentypeless.android.ime;
                                public final class OpenTypelessImeService extends android.content.Context {
                                    static final class LastVoiceCommit {
                                        final String rawText = "copied";
                                        final com.opentypeless.android.editor.CommitRecord teachRecord = null;
                                    }
                                    private void teachCorrection() {
                                        LastVoiceCommit commit = new LastVoiceCommit();
                                        String copied = commit.rawText;
                                        com.opentypeless.android.HistoryActivity.createTeachIntent(
                                                        this, commit.teachRecord, -1L)
                                                .putExtra("raw", copied);
                                    }
                                    private void showMoreMenu(android.view.View view) {
                                        Teach(commit());
                                    }
                                    private void Teach(LastVoiceCommit commit) {
                                        com.opentypeless.android.personalization.TeachCorrectionResolver
                                                .isEligible(commit.teachRecord);
                                    }
                                    private LastVoiceCommit commit() { return new LastVoiceCommit(); }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.ForeignTeachCaller",
                                """
                                package com.opentypeless.android.provider;
                                public final class ForeignTeachCaller {
                                    public android.content.Intent launch(
                                            android.content.Context context,
                                            com.opentypeless.android.editor.CommitRecord record) {
                                        return com.opentypeless.android.HistoryActivity
                                                .createTeachIntent(context, record, -1L);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("VOC008_TEACH_AUTHORITY");
        result.assertFailureContains("VOC008_TEACH_FACTORY_SHAPE");
        result.assertFailureContains("VOC008_TEACH_RESOLVER_SHAPE");
        result.assertFailureContains("VOC008_TEACH_FACTORY_CALLER");
    }

    @Test
    public void textProcessingPipelineRejectsShapeLeakWrongCallerAndBindingDrift()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.TextProcessingPipeline",
                                """
                                package com.opentypeless.android.ime;
                                public interface TextProcessingPipeline {
                                    record LlmRequest(String secret) {}
                                    android.view.inputmethod.InputConnection leak();
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.StagedTextProcessingPipeline",
                                """
                                package com.opentypeless.android.ime;
                                final class StagedTextProcessingPipeline
                                        implements TextProcessingPipeline {
                                    private final Object arbitrary = new Object();
                                    public android.view.inputmethod.InputConnection leak() {
                                        return null;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoicePipelineRuntime",
                                """
                                package com.opentypeless.android.ime;
                                final class VoicePipelineRuntime {
                                    private TextProcessingPipeline textProcessingPipeline;
                                    VoicePipelineRuntime(Object context) {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.TextStageLeak",
                                """
                                package com.opentypeless.android.provider;
                                final class TextStageLeak {
                                    com.opentypeless.android.ime.TextProcessingPipeline pipeline;
                                }
                                """)));
        GateResult result = audit(project);
        result.assertFailureContains("VOC003_PIPELINE_SHAPE");
        result.assertFailureContains("VOC003_REQUEST_SHAPE");
        result.assertFailureContains("VOC003_REQUEST_REDACTION");
        result.assertFailureContains("VOC003_STAGED_PIPELINE_SHAPE");
        result.assertFailureContains("VOC003_PIPELINE_SCOPE_TRANSFER");
        result.assertFailureContains("VOC003_PIPELINE_CALLER");
    }

    @Test
    public void productionTextProcessingBinariesAndExactStageEdgesFailClosedWhenAbsent()
            throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("VOC003_BINARY_MISSING");
        result.assertFailureContains("VOC003_EXACT_EDGE");
        result.assertFailureContains("VOC005_BINARY_MISSING");
        result.assertFailureContains("VOC005_EXACT_EDGE");
        result.assertFailureContains("VOC006_BINARY_MISSING");
        result.assertFailureContains("VOC006_EXACT_EDGE");
    }

    @Test
    public void deterministicPersonalizationRejectsCapabilityLeakWrongCallerAndDirectBinding()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.data.PersonalizationSnapshot",
                                """
                                package com.opentypeless.android.data;
                                public final class PersonalizationSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.personalization.ProcessingResult",
                                """
                                package com.opentypeless.android.personalization;
                                public final class ProcessingResult {}
                                """),
                        source(
                                "com.opentypeless.android.personalization.PersonalizedTextProcessor",
                                """
                                package com.opentypeless.android.personalization;
                                public final class PersonalizedTextProcessor {
                                    public static ProcessingResult apply(
                                            String input,
                                            com.opentypeless.android.data.PersonalizationSnapshot snapshot) {
                                        return new ProcessingResult();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.TextProcessingPipeline",
                                """
                                package com.opentypeless.android.ime;
                                public interface TextProcessingPipeline {
                                    enum DeterministicFailurePolicy { PRESERVE_INPUT, PROPAGATE }
                                    interface DeterministicStage {
                                        com.opentypeless.android.personalization.ProcessingResult apply(
                                            String input,
                                            com.opentypeless.android.data.PersonalizationSnapshot snapshot,
                                            DeterministicFailurePolicy policy);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.DeterministicPersonalizationStage",
                                """
                                package com.opentypeless.android.ime;
                                public final class DeterministicPersonalizationStage
                                        implements TextProcessingPipeline.DeterministicStage {
                                    private android.view.inputmethod.InputConnection retained;
                                    public com.opentypeless.android.personalization.ProcessingResult apply(
                                            String input,
                                            com.opentypeless.android.data.PersonalizationSnapshot snapshot,
                                            TextProcessingPipeline.DeterministicFailurePolicy policy) {
                                        return com.opentypeless.android.personalization
                                                .PersonalizedTextProcessor.apply(input, snapshot);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoicePipelineRuntime",
                                """
                                package com.opentypeless.android.ime;
                                final class VoicePipelineRuntime {
                                    private TextProcessingPipeline textProcessingPipeline;
                                    VoicePipelineRuntime(Object context) {
                                        com.opentypeless.android.personalization
                                                .PersonalizedTextProcessor.apply("text", null);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.PersonalizationStageLeak",
                                """
                                package com.opentypeless.android.provider;
                                public final class PersonalizationStageLeak {
                                    public com.opentypeless.android.ime
                                            .DeterministicPersonalizationStage retained;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("VOC005_PERSONALIZATION_STAGE_SHAPE");
        result.assertFailureContains("VOC005_PIPELINE_BINDING");
        result.assertFailureContains("VOC003_PIPELINE_SCOPE_TRANSFER");
    }

    @Test
    public void llmAndIntegrityStagesRejectShapeLeakWrongCallerAndDirectBinding()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.TextProcessingPipeline",
                                """
                                package com.opentypeless.android.ime;
                                public interface TextProcessingPipeline {
                                    record LlmRequest(String text) {}
                                    record IntegrityRequest(String source, String candidate) {}
                                    interface OptionalLlmStage {
                                        String apply(
                                            LlmRequest request,
                                            java.util.function.BooleanSupplier cancelled)
                                            throws Exception;
                                    }
                                    interface IntegrityGuardStage {
                                        com.opentypeless.android.transform.IntegrityResult apply(
                                            IntegrityRequest request);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.transform.IntegrityResult",
                                """
                                package com.opentypeless.android.transform;
                                public final class IntegrityResult {}
                                """),
                        source(
                                "com.opentypeless.android.transform.TranscriptIntegrityGuard",
                                """
                                package com.opentypeless.android.transform;
                                public final class TranscriptIntegrityGuard {
                                    public static IntegrityResult validate(
                                            String source, String candidate, Object mode, Object snapshot) {
                                        return new IntegrityResult();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.net.OpenAiCompatibleClient",
                                """
                                package com.opentypeless.android.net;
                                public final class OpenAiCompatibleClient {
                                    public String complete(
                                            String system, String user, Object settings,
                                            java.util.function.BooleanSupplier cancelled) {
                                        return user;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.OpenAiOptionalLlmStage",
                                """
                                package com.opentypeless.android.ime;
                                public final class OpenAiOptionalLlmStage
                                        implements TextProcessingPipeline.OptionalLlmStage {
                                    private android.view.inputmethod.InputConnection retained;
                                    public String apply(
                                            TextProcessingPipeline.LlmRequest request,
                                            java.util.function.BooleanSupplier cancelled) {
                                        return request.text();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.TranscriptIntegrityGuardStage",
                                """
                                package com.opentypeless.android.ime;
                                public final class TranscriptIntegrityGuardStage
                                        implements TextProcessingPipeline.IntegrityGuardStage {
                                    private final String retained = "plaintext";
                                    public com.opentypeless.android.transform.IntegrityResult apply(
                                            TextProcessingPipeline.IntegrityRequest request) {
                                        return new com.opentypeless.android.transform.IntegrityResult();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoicePipelineRuntime",
                                """
                                package com.opentypeless.android.ime;
                                final class VoicePipelineRuntime {
                                    private TextProcessingPipeline textProcessingPipeline;
                                    VoicePipelineRuntime(Object context) {
                                        new com.opentypeless.android.net.OpenAiCompatibleClient()
                                            .complete("system", "user", null, () -> false);
                                        com.opentypeless.android.transform.TranscriptIntegrityGuard
                                            .validate("source", "candidate", null, null);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.StageLeak",
                                """
                                package com.opentypeless.android.provider;
                                public final class StageLeak {
                                    public com.opentypeless.android.ime.OpenAiOptionalLlmStage llm;
                                    public com.opentypeless.android.ime.TranscriptIntegrityGuardStage guard;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("VOC006_LLM_STAGE_SHAPE");
        result.assertFailureContains("VOC006_INTEGRITY_STAGE_SHAPE");
        result.assertFailureContains("VOC006_PIPELINE_BINDING");
        result.assertFailureContains("VOC003_PIPELINE_SCOPE_TRANSFER");
    }

    @Test
    public void voiceResultRejectsShapeLeakForgedConstructionAndLegacyConsumer()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.StageProvenance",
                                """
                                package com.opentypeless.android.ime;
                                public record StageProvenance(
                                        Stage stage, Disposition disposition, String secret) {
                                    public enum Stage { RECOGNITION }
                                    public enum Disposition { CAPTURED }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoiceResult",
                                """
                                package com.opentypeless.android.ime;
                                public record VoiceResult(
                                        String rawText,
                                        String finalText,
                                        java.util.List<StageProvenance> provenance,
                                        android.view.inputmethod.InputConnection connection) {}
                                """),
                        source(
                                "com.opentypeless.android.ime.DictationResult",
                                """
                                package com.opentypeless.android.ime;
                                public record DictationResult(
                                        VoiceResult voiceResult,
                                        String rawText,
                                        String finalText) {}
                                """),
                        source(
                                "com.opentypeless.android.provider.ForgedVoiceResult",
                                """
                                package com.opentypeless.android.provider;
                                public final class ForgedVoiceResult {
                                    String forge() {
                                        com.opentypeless.android.ime.StageProvenance provenance =
                                                new com.opentypeless.android.ime.StageProvenance(
                                                        com.opentypeless.android.ime.StageProvenance.Stage.RECOGNITION,
                                                        com.opentypeless.android.ime.StageProvenance.Disposition.CAPTURED,
                                                        "secret");
                                        com.opentypeless.android.ime.VoiceResult result =
                                                new com.opentypeless.android.ime.VoiceResult(
                                                        "raw", "final", java.util.List.of(provenance), null);
                                        return new com.opentypeless.android.ime.DictationResult(
                                                result, "raw", "final").rawText();
                                    }
                                }
                                """)));
        GateResult result = audit(project);
        result.assertFailureContains("VOC004_PROVENANCE_SHAPE");
        result.assertFailureContains("VOC004_RESULT_SHAPE");
        result.assertFailureContains("VOC004_RESULT_REDACTION");
        result.assertFailureContains("VOC004_DICTATION_ENVELOPE");
        result.assertFailureContains("VOC004_PROVENANCE_CALLER");
        result.assertFailureContains("VOC004_RESULT_CALLER");
        result.assertFailureContains("VOC004_RESULT_CONSUMER");
    }

    @Test
    public void productionVoiceResultBinariesAndExactPublicationEdgesFailClosedWhenAbsent()
            throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("VOC004_BINARY_MISSING");
        result.assertFailureContains("VOC004_EXACT_EDGE");
    }

    @Test
    public void productionUndoAndRawEdgeBaselinesFailClosedWhenExpectedEdgesShift()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                public final class EditorSessionManager {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorTransactionManager",
                                """
                                package com.opentypeless.android.editor.host;
                                final class EditorTransactionManager {}
                                """)));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("EDT008_EXACT_EDGE");
        result.assertFailureContains("prepareReplaceTransition");
        result.assertFailureContains("EDT011_EXACT_EDGE");
        result.assertFailureContains("undoCommit");
        result.assertFailureContains("EDT012_EXACT_EDGE");
        result.assertFailureContains("restoreRawCommit");
        result.assertFailureContains("EDT013_EXACT_EDGE");
        result.assertFailureContains("restoreCommittedAndClassify");
        result.assertFailureContains("EDT014_BINARY_MISSING");
        result.assertFailureContains("EditorTransactionAudit");
        result.assertFailureContains("EDT014_EXACT_EDGE");
        result.assertFailureContains("auditReceipt");
    }

    @Test
    public void unknownInvokeDynamicBootstrapIsRejected() throws Exception {
        Path project = Files.createTempDirectory(workspace, "unknown-bootstrap-");
        writeUnknownBootstrapClass(
                project,
                "com/opentypeless/android/provider/UnknownBootstrap");

        GateResult result = audit(project);

        result.assertFailureContains("UnknownBootstrap");
        result.assertFailureContains("UNKNOWN_BOOTSTRAP");
    }

    @Test
    public void constantDynamicIsRejectedEvenWithAnOtherwiseAllowedBootstrap() throws Exception {
        Path project = Files.createTempDirectory(workspace, "constant-dynamic-");
        writeConstantDynamicClass(
                project,
                "com/opentypeless/android/provider/ConstantDynamicUser");

        GateResult result = audit(project);

        result.assertFailureContains("ConstantDynamicUser");
        result.assertFailureContains("CONSTANT_DYNAMIC_NOT_ALLOWED");
    }

    @Test
    public void legacyNestmateCannotAddAnUninventoriedEditorWrite() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.OpenTypelessImeService",
                                """
                                package com.opentypeless.android.ime;
                                public final class OpenTypelessImeService {
                                    static final class HiddenWriter {
                                        boolean write(android.view.inputmethod.InputConnection value) {
                                            return value.commitText("unsafe", 1);
                                        }
                                    }
                                }
                                """)));

        GateResult result = audit(project);

        result.assertFailureContains("HiddenWriter");
        result.assertFailureContains("EDITOR_WRITE_OWNER");
    }

    @Test
    public void exactLegacyOwnerCannotAddCursorUpdateMutators() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.ime.VoiceCompositionSession",
                                """
                                package com.opentypeless.android.ime;
                                public final class VoiceCompositionSession {
                                    boolean request(android.view.inputmethod.InputConnection value) {
                                        return value.requestCursorUpdates(1)
                                                && value.requestCursorUpdates(1, 2);
                                    }
                                }
                                """)));

        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));

        result.assertFailureContains("requestCursorUpdates");
        result.assertFailureContains("EDITOR_WRITE_BASELINE");
    }

    @Test
    public void missingAndEmptyManifestsFailClosed() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.model.Safe",
                                "package com.opentypeless.android.model; final class Safe {}")));
        Path missing = workspace.resolve("missing-" + System.nanoTime());
        Path empty = Files.createTempFile(workspace, "empty-", ".paths");
        Path all = writeManifest(List.of(project, androidAndThirdPartyClasses));

        runGate(missing, all).assertFailureContains("manifest");
        runGate(empty, all).assertFailureContains("manifest");
    }

    @Test
    public void projectArtifactMustAlsoAppearInAllManifest() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.model.Safe",
                                "package com.opentypeless.android.model; final class Safe {}")));

        GateResult result = runGate(
                writeManifest(List.of(project)),
                writeManifest(List.of(androidAndThirdPartyClasses)));

        result.assertFailureContains("PROJECT");
        result.assertFailureContains("ALL");
    }

    @Test
    public void missingArtifactAndBinaryPathMismatchFailClosed() throws Exception {
        Path missingArtifact = workspace.resolve("missing-artifact-" + System.nanoTime());
        GateResult missingResult = runGate(
                writeManifest(List.of(missingArtifact)),
                writeManifest(List.of(missingArtifact, androidAndThirdPartyClasses)));
        missingResult.assertFailureContains("artifact does not exist");

        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.model.Misplaced",
                                "package com.opentypeless.android.model; final class Misplaced {}")));
        Path expected = project.resolve("com/opentypeless/android/model/Misplaced.class");
        Path misplaced = project.resolve("com/opentypeless/android/model/Wrong.class");
        Files.move(expected, misplaced);

        audit(project).assertFailureContains("binary/path mismatch");
    }

    @Test
    public void duplicateProjectClassFailsClosed() throws Exception {
        Map.Entry<String, String> first = source(
                "com.opentypeless.android.model.Duplicate",
                """
                package com.opentypeless.android.model;
                final class Duplicate { static final int VERSION = 1; }
                """);
        Map.Entry<String, String> second = source(
                "com.opentypeless.android.model.Duplicate",
                """
                package com.opentypeless.android.model;
                final class Duplicate { static final int VERSION = 2; }
                """);
        Path firstOutput = compileProject(Map.ofEntries(first));
        Path secondOutput = compileProject(Map.ofEntries(second));
        List<Path> projectArtifacts = List.of(firstOutput, secondOutput);

        GateResult result = runGate(
                writeManifest(projectArtifacts),
                writeManifest(List.of(firstOutput, secondOutput, androidAndThirdPartyClasses)));

        result.assertFailureContains("DUPLICATE");
        result.assertFailureContains("Duplicate");
    }

    @Test
    public void corruptClassFileFailsClosed() throws Exception {
        Path project = Files.createTempDirectory(workspace, "corrupt-classes-");
        Path corrupt = project.resolve("com/opentypeless/android/model/Corrupt.class");
        Files.createDirectories(corrupt.getParent());
        Files.write(corrupt, new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});

        GateResult result = audit(project);

        result.assertFailureContains("malformed class");
    }

    @Test
    public void unsafeJarEntryAndEmptyJarFailClosed() throws Exception {
        Path traversalJar = Files.createTempFile(workspace, "traversal-", ".jar");
        try (OutputStream output = Files.newOutputStream(traversalJar);
                JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("../com/opentypeless/android/model/Escape.class"));
            jar.write(new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
            jar.closeEntry();
        }
        Path emptyJar = Files.createTempFile(workspace, "empty-", ".jar");
        try (OutputStream output = Files.newOutputStream(emptyJar);
                JarOutputStream ignored = new JarOutputStream(output)) {
            // Deliberately empty.
        }

        audit(traversalJar).assertFailureContains("JAR");
        audit(emptyJar).assertFailureContains("no classes");
    }

    @Test
    public void validProjectJarIsAudited() throws Exception {
        Path classes = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.model.SafeJarClass",
                                "package com.opentypeless.android.model; final class SafeJarClass {}")));
        Path projectJar = jar(classes, "safe-project.jar");

        audit(projectJar).assertSuccess();
    }

    @Test
    public void keyboardHostAndFacadeRejectWrongShapeOrCaller() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.editor.EditorSessionSnapshot",
                                """
                                package com.opentypeless.android.editor;
                                public final class EditorSessionSnapshot {}
                                """),
                        source(
                                "com.opentypeless.android.editor.EditorTransactionResult",
                                """
                                package com.opentypeless.android.editor;
                                public interface EditorTransactionResult {}
                                """),
                        source(
                                "com.opentypeless.android.editor.host.EditorSessionManager",
                                """
                                package com.opentypeless.android.editor.host;

                                public final class EditorSessionManager {
                                    public interface KeyboardHost {
                                        android.view.inputmethod.EditorInfo currentEditorInfo();
                                        android.view.inputmethod.InputConnection currentInputConnection();
                                        Object leakedCapabilitySurface();
                                    }

                                    public com.opentypeless.android.editor.EditorTransactionResult
                                            insertKeyboardText(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot,
                                                    String text) { return null; }
                                    public com.opentypeless.android.editor.EditorTransactionResult
                                            deleteKeyboardBackward(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot) {
                                        return null;
                                    }
                                    public com.opentypeless.android.editor.EditorTransactionResult
                                            performKeyboardEnter(
                                                    KeyboardHost host,
                                                    com.opentypeless.android.editor.EditorSessionSnapshot snapshot) {
                                        return null;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.KeyboardCaller",
                                """
                                package com.opentypeless.android.provider;
                                final class KeyboardCaller {
                                    Object call(
                                            com.opentypeless.android.editor.host.EditorSessionManager manager,
                                            com.opentypeless.android.editor.host.EditorSessionManager.KeyboardHost host,
                                            com.opentypeless.android.editor.EditorSessionSnapshot snapshot) {
                                        return manager.insertKeyboardText(host, snapshot, "x");
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("KEYBOARD_FACADE_CALLER");
        result.assertFailureContains("KEYBOARD_HOST_SHAPE");
        result.assertFailureContains("KEYBOARD_TRANSACTION_EDGE");
    }

    @Test
    public void cfg001ProviderModelsRejectOpenShapesSerializationNetworkAndExtraBinaries()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.config.ProviderConfig",
                                """
                                package com.opentypeless.android.config;
                                public interface ProviderConfig extends java.io.Serializable {
                                    String apiKey();
                                    java.net.URL endpoint();
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.SecretRef",
                                """
                                package com.opentypeless.android.config;
                                public record SecretRef(String secretValue)
                                        implements java.io.Serializable {}
                                """),
                        source(
                                "com.opentypeless.android.config.HiddenCredential",
                                """
                                package com.opentypeless.android.config;
                                final class HiddenCredential { byte[] value; }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG001_PROVIDER_SHAPE");
        result.assertFailureContains("CFG001_SECRET_REF_SHAPE");
        result.assertFailureContains("CFG001_DOMAIN_DEPENDENCY");
        result.assertFailureContains("CFG001_EXTRA_BINARY");
    }

    @Test
    public void cfg002RouteRejectsOpenShapesAuthorityLeaksAndVocabularyDrift()
            throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.config.RecognitionRoute",
                        """
                        package com.opentypeless.android.config;
                        public record RecognitionRoute(
                                String id,
                                java.util.List<Object> steps,
                                java.net.URI endpoint)
                                implements java.io.Serializable {
                            public record RouteStep(String providerId, String secretValue) {}
                            public record RetryPolicy(
                                    int maximumAttempts,
                                    java.util.Set<String> retryOn) {}
                            public enum PrivacyClass { ON_DEVICE, PUBLIC_NETWORK }
                            public enum ProviderCapability { STREAMING, EXECUTE }
                            public enum FailureClass { CANCELLED, RETRY_FOREVER }
                            public enum ConfirmationPolicy { NOT_REQUIRED }
                        }
                        """));

        GateResult result = audit(project);
        result.assertFailureContains("CFG002_ROUTE_SHAPE");
        result.assertFailureContains("CFG002_ROUTE_STEP_SHAPE");
        result.assertFailureContains("CFG002_RETRY_POLICY_SHAPE");
        result.assertFailureContains("CFG002_ENUM_SHAPE");
        result.assertFailureContains("CFG002_AUTHORITY_BOUNDARY");
        result.assertFailureContains("CFG001_DOMAIN_DEPENDENCY");
    }

    @Test
    public void rec001RejectsPartialNameInferredCapabilitiesAndLeakyDescriptors()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.settings.RecognitionBackend",
                                """
                                package com.opentypeless.android.settings;
                                public enum RecognitionBackend {
                                    OPENAI_COMPATIBLE,
                                    LOCAL_OFFLINE,
                                    DASHSCOPE_STREAMING,
                                    SYSTEM_ON_DEVICE,
                                    SYSTEM_DEFAULT
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.RecognitionRoute",
                                """
                                package com.opentypeless.android.config;
                                public final class RecognitionRoute {
                                    public enum PrivacyClass {
                                        ON_DEVICE, LOCAL_NETWORK, PUBLIC_NETWORK
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.ProviderCapabilities",
                                """
                                package com.opentypeless.android.recognition;
                                public record ProviderCapabilities(
                                        boolean streaming,
                                        boolean partial,
                                        String providerName)
                                        implements java.io.Serializable {
                                    public enum AudioFormat { ANY, MP3 }
                                    public static ProviderCapabilities declaredForBackend(
                                            com.opentypeless.android.settings.RecognitionBackend backend) {
                                        return backend.name().contains("STREAM")
                                                ? new ProviderCapabilities(true, true, backend.name())
                                                : new ProviderCapabilities(false, false, backend.name());
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.ProviderDescriptor",
                                """
                                package com.opentypeless.android.recognition;
                                public record ProviderDescriptor(
                                        String name,
                                        java.net.URL endpoint,
                                        String secretValue,
                                        Object provider) implements java.io.Serializable {
                                    public static ProviderDescriptor declaredForBackend(
                                            com.opentypeless.android.settings.RecognitionBackend backend) {
                                        return null;
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC001_CAPABILITY_SHAPE");
        result.assertFailureContains("REC001_AUDIO_FORMAT_SHAPE");
        result.assertFailureContains("REC001_DESCRIPTOR_SHAPE");
        result.assertFailureContains("REC001_EXPLICIT_DECLARATION");
        result.assertFailureContains("REC001_DESCRIPTOR_REDACTION");
        result.assertFailureContains("REC001_DOMAIN_DEPENDENCY");
    }

    @Test
    public void rec002RejectsOpenLeakyEventsMetadataAndNonLinearValidators()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.recognition.RecognitionEvent",
                                """
                                package com.opentypeless.android.recognition;
                                public interface RecognitionEvent extends java.io.Serializable {
                                    record Partial(
                                            String sessionId,
                                            long sequence,
                                            String text,
                                            String rawError)
                                            implements RecognitionEvent {
                                        @Override public String toString() {
                                            return sessionId + text + rawError;
                                        }
                                    }
                                    record Final(String sessionId, long sequence, String text)
                                            implements RecognitionEvent {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionMetadata",
                                """
                                package com.opentypeless.android.recognition;
                                public record RecognitionMetadata(
                                        String detectedLanguageTag,
                                        Float confidence,
                                        Long audioDurationMs,
                                        String transcript,
                                        java.net.URL endpoint)
                                        implements java.io.Serializable {
                                    @Override public String toString() {
                                        return detectedLanguageTag + transcript + endpoint;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionEventValidator",
                                """
                                package com.opentypeless.android.recognition;
                                public class RecognitionEventValidator {
                                    private final java.util.List<RecognitionEvent> events =
                                            new java.util.ArrayList<>();
                                    private final java.util.concurrent.Executor executor =
                                            Runnable::run;
                                    public Disposition accept(RecognitionEvent event) {
                                        events.add(event);
                                        return Disposition.ACCEPTED;
                                    }
                                    @Override public String toString() {
                                        return events.toString();
                                    }
                                    public enum Disposition { ACCEPTED }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC002_DOMAIN_DEPENDENCY");
        result.assertFailureContains("REC002_EVENT_SHAPE");
        result.assertFailureContains("REC002_EVENT_BOUNDS");
        result.assertFailureContains("REC002_EVENT_REDACTION");
        result.assertFailureContains("REC002_METADATA_SHAPE");
        result.assertFailureContains("REC002_METADATA_BOUNDS");
        result.assertFailureContains("REC002_VALIDATOR_SHAPE");
        result.assertFailureContains("REC002_SEQUENCE_TERMINAL");
    }

    @Test
    public void str001RejectsOpenLeakyWireShapesRawErrorsAndDecodeCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.net.streaming.StreamingRecognitionWireEvent",
                                """
                                package com.opentypeless.android.net.streaming;

                                import java.net.URL;

                                public final class StreamingRecognitionWireEvent {
                                    URL endpoint;
                                    static Object decode(String json) {
                                        try {
                                            throw new IllegalArgumentException(json);
                                        } catch (RuntimeException error) {
                                            error.getMessage();
                                            return json;
                                        }
                                    }
                                    static final class Stream {
                                        private String transcript;
                                        Result accept(String json) { return new Accepted(json); }
                                        @Override public String toString() { return transcript; }
                                    }
                                    sealed interface Result permits Accepted, Rejected {}
                                    record Accepted(String event) implements Result {}
                                    record Rejected(String reason) implements Result {}
                                    enum Rejection { MALFORMED }
                                }
                                """),
                        source(
                                "com.opentypeless.android.net.streaming.UnsafeStreamingConsumer",
                                """
                                package com.opentypeless.android.net.streaming;

                                final class UnsafeStreamingConsumer {
                                    StreamingRecognitionWireEvent.Stream stream;
                                    Object parse(String json) {
                                        return StreamingRecognitionWireEvent.decode(json);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("STR001_DOMAIN_DEPENDENCY");
        result.assertFailureContains("STR001_WIRE_SHAPE");
        result.assertFailureContains("STR001_EVENT_MAPPING");
        result.assertFailureContains("STR001_SEQUENCE_TERMINAL");
        result.assertFailureContains("STR001_RESULT_SHAPE");
        result.assertFailureContains("STR001_REDACTION");
        result.assertFailureContains("STR001_RAW_ERROR");
        result.assertFailureContains("STR001_SCOPE");
        result.assertFailureContains("STR001_RAW_DECODE_CALLER");
    }

    @Test
    public void str002RejectsLeakyOpenUnboundedTransportAndOutsideClientReferences()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.net.streaming.StreamingRecognitionWebSocketClient",
                                """
                                package com.opentypeless.android.net.streaming;

                                public class StreamingRecognitionWebSocketClient {
                                    java.io.Serializable retainedState;

                                    public String leakFailure(Throwable error) {
                                        return error.getMessage();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.net.streaming.UnsafeStreamingClientConsumer",
                                """
                                package com.opentypeless.android.net.streaming;

                                final class UnsafeStreamingClientConsumer {
                                    StreamingRecognitionWebSocketClient retainedClient;
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public interface RecognitionProvider<R> {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.WebSocketStreamingProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public class WebSocketStreamingProvider
                                        implements RecognitionProvider<Object> {
                                    java.util.List<byte[]> unboundedAudio = new java.util.ArrayList<>();
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("STR002_ADAPTER_DEPENDENCY");
        result.assertFailureContains("STR002_CLIENT_SHAPE");
        result.assertFailureContains("STR002_CLIENT_CONTRACT");
        result.assertFailureContains("STR002_FAILURE_REDACTION");
        result.assertFailureContains("STR002_CLIENT_CALLER");
        result.assertFailureContains("STR002_PROVIDER_SHAPE");
        result.assertFailureContains("STR002_RECONNECT_BOUND");
    }

    @Test
    public void str003RejectsLeakyOpenUnboundedQwenAdapterAndOutsideReferences()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.net.streaming.Qwen3AsrVllmClient",
                                """
                                package com.opentypeless.android.net.streaming;

                                public class Qwen3AsrVllmClient
                                        implements java.io.Serializable {
                                    java.io.File retainedTranscript;

                                    public String leak(Throwable error) {
                                        return error.getMessage();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public interface RecognitionProvider<R> {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.Qwen3AsrVllmProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public class Qwen3AsrVllmProvider
                                        implements RecognitionProvider<Object> {
                                    java.io.File retainedAudio;
                                    public interface ProbeWorker {
                                        void execute(java.lang.Runnable action);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.UnsafeQwenConsumer",
                                """
                                package com.opentypeless.android.recognition;

                                final class UnsafeQwenConsumer {
                                    com.opentypeless.android.net.streaming.Qwen3AsrVllmClient client;
                                    Qwen3AsrVllmProvider.ProbeWorker worker;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("STR003_ADAPTER_DEPENDENCY");
        result.assertFailureContains("STR003_CLIENT_SHAPE");
        result.assertFailureContains("STR003_TRANSPORT_BOUND");
        result.assertFailureContains("STR003_FAILURE_REDACTION");
        result.assertFailureContains("STR003_PROVIDER_SHAPE");
        result.assertFailureContains("STR003_PROBE_BOUND");
        result.assertFailureContains("STR003_CLIENT_CALLER");
        result.assertFailureContains("STR003_ADAPTER_SCOPE");
    }

    @Test
    public void str005RejectsLeakyUnboundedLocalStreamingModelDriftAndOutsideCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public interface RecognitionProvider<R> {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.LocalStreamingProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public class LocalStreamingProvider
                                        implements RecognitionProvider<Object>,
                                                java.io.Serializable {
                                    android.view.inputmethod.InputConnection editor;
                                    java.net.Socket network;
                                    byte[] unboundedPcm;

                                    public interface Backend {}

                                    public String leak(Throwable error) {
                                        return error.getMessage();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.offline.LocalRealtimeRecognitionClient",
                                """
                                package com.opentypeless.android.offline;

                                public final class LocalRealtimeRecognitionClient {
                                    public interface Listener {
                                        void onPartial(String text);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.offline.OfflineStreamingModelSpec",
                                """
                                package com.opentypeless.android.offline;

                                public record OfflineStreamingModelSpec(String revision) {
                                    public static final String PARAFORMER_REVISION = "latest";
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.UnsafeLocalStreamingConsumer",
                                """
                                package com.opentypeless.android.recognition;

                                final class UnsafeLocalStreamingConsumer {
                                    LocalStreamingProvider.Backend backend;
                                    com.opentypeless.android.offline.LocalRealtimeRecognitionClient
                                            client;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("STR005_ADAPTER_DEPENDENCY");
        result.assertFailureContains("STR005_PROVIDER_SHAPE");
        result.assertFailureContains("STR005_FAILURE_REDACTION");
        result.assertFailureContains("STR005_CLIENT_CONTRACT");
        result.assertFailureContains("STR005_MODEL_PIN");
        result.assertFailureContains("STR005_PRODUCTION_WIRING");
        result.assertFailureContains("STR005_CLIENT_CALLER");
    }

    @Test
    public void str006RejectsLeakyUnboundedOpenCompositeAndOutsideReferences()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public interface RecognitionProvider<R> {
                                    interface Session {}
                                    interface EventSink { void onEvent(Object event); }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.LocalStreamingProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public final class LocalStreamingProvider {
                                    public static final class StartRequest {}
                                    public interface StreamingSession {}
                                    public interface Backend {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.SenseVoiceFinalProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public final class SenseVoiceFinalProvider {
                                    public static final class StartRequest {}
                                    public interface Backend {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.TwoStageStreamingProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public class TwoStageStreamingProvider
                                        implements RecognitionProvider<Object>,
                                                java.io.Serializable {
                                    android.view.inputmethod.InputConnection editor;
                                    java.net.Socket network;
                                    byte[] unboundedPcm;
                                    LocalStreamingProvider.Backend streamingBackend;
                                    SenseVoiceFinalProvider.Backend finalizerBackend;

                                    public static final class StartRequest {
                                        public String language;
                                    }

                                    public interface StreamingSession
                                            extends RecognitionProvider.Session {}

                                    public interface Worker {}

                                    public record RequestClaim(String language) {}

                                    public static final class SessionState
                                            implements StreamingSession {
                                        public String transcript;
                                    }

                                    public static final class PcmBuffer {
                                        public byte[] bytes;
                                    }

                                    public static final class AudioClaim {
                                        public byte[] pcm;
                                    }

                                    public static final class SingleWorker implements Worker {}

                                    public String leak(Throwable error) {
                                        return error.getMessage();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.UnsafeTwoStageConsumer",
                                """
                                package com.opentypeless.android.recognition;

                                final class UnsafeTwoStageConsumer {
                                    TwoStageStreamingProvider.StartRequest request;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("STR006_ADAPTER_DEPENDENCY");
        result.assertFailureContains("STR006_CHILD_SCOPE");
        result.assertFailureContains("STR006_FAILURE_REDACTION");
        result.assertFailureContains("STR006_PROVIDER_SHAPE");
        result.assertFailureContains("STR006_REQUEST_BOUND");
        result.assertFailureContains("STR006_SESSION_SHAPE");
        result.assertFailureContains("STR006_BOUNDED_AUDIO");
        result.assertFailureContains("STR006_WORKER_BOUND");
        result.assertFailureContains("STR006_PRODUCTION_WIRING");
    }

    @Test
    public void str010RejectsLeakyUnboundDoublePathAndOutsideControllerCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "com.opentypeless.android.ime.DictationRequest",
                                """
                                package com.opentypeless.android.ime;
                                public final class DictationRequest {}
                                """),
                        source(
                                "com.opentypeless.android.ime.VoiceController",
                                """
                                package com.opentypeless.android.ime;
                                public interface VoiceController {
                                    enum State { IDLE }
                                    interface Events { void onError(String message); }
                                    boolean start(DictationRequest request, Events events);
                                    void stop();
                                    void cancel();
                                    State state();
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.VoicePipelineAdapter",
                                """
                                package com.opentypeless.android.ime;
                                public class VoicePipelineAdapter implements VoiceController {
                                    public boolean start(DictationRequest request, Events events) {
                                        return true;
                                    }
                                    public void stop() {}
                                    public void cancel() {}
                                    public State state() { return State.IDLE; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.ProviderCircuitBreaker",
                                """
                                package com.opentypeless.android.recognition;
                                public final class ProviderCircuitBreaker {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionRouter",
                                """
                                package com.opentypeless.android.recognition;
                                public final class RecognitionRouter {
                                    public interface Decision {}
                                    public static final class Attempt {}
                                    public static final class AttemptReady implements Decision {}
                                    public static final class RouteFailed implements Decision {}
                                    public static final class Completed implements Decision {}
                                    public static final class PrivacyAuthorization {}
                                    public enum ConfirmationDecision { CANCEL }
                                    public Object onConfirmation(Object request,
                                            ConfirmationDecision decision) { return request; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionRouterVoiceController",
                                """
                                package com.opentypeless.android.recognition;

                                public class RecognitionRouterVoiceController
                                        implements com.opentypeless.android.ime.VoiceController,
                                                java.io.Serializable {
                                    android.view.inputmethod.InputConnection editor;
                                    com.opentypeless.android.ime.VoiceController delegate;
                                    RecognitionRouter.ConfirmationDecision bypass;

                                    public RecognitionRouterVoiceController(
                                            android.content.Context context,
                                            com.opentypeless.android.ime.VoicePipelineAdapter delegate) {
                                        this.delegate = delegate;
                                    }

                                    public boolean start(
                                            com.opentypeless.android.ime.DictationRequest request,
                                            Events events) {
                                        return delegate.start(request, events);
                                    }
                                    public void stop() { delegate.stop(); }
                                    public void cancel() { delegate.cancel(); }
                                    public State state() { return delegate.state(); }
                                    public String leak(Throwable failure) {
                                        return failure.getMessage();
                                    }

                                    public interface Environment {}
                                    public record PreparedRoute(String transcript) {}
                                    public record ActiveRun(String transcript) {}
                                    public static final class Preparation { public long generation; }
                                    public record PreparationResult(String transcript) {}

                                    public Object bypass(RecognitionRouter router) {
                                        return router.onConfirmation(
                                                new Object(),
                                                RecognitionRouter.ConfirmationDecision.CANCEL);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionRouterVoiceConfig",
                                """
                                package com.opentypeless.android.recognition;
                                public class RecognitionRouterVoiceConfig {
                                    public static com.opentypeless.android.ime.VoiceController select(
                                            android.content.Context context,
                                            com.opentypeless.android.ime.VoicePipelineAdapter delegate) {
                                        delegate.start(null, null);
                                        return new RecognitionRouterVoiceController(context, delegate);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.RouterVoiceOutsider",
                                """
                                package com.opentypeless.android.provider;
                                public final class RouterVoiceOutsider {
                                    public com.opentypeless.android.recognition
                                            .RecognitionRouterVoiceController controller;
                                    public Object bypass(
                                            android.content.Context context,
                                            com.opentypeless.android.ime.VoicePipelineAdapter delegate) {
                                        controller = new com.opentypeless.android.recognition
                                                .RecognitionRouterVoiceController(context, delegate);
                                        return com.opentypeless.android.recognition
                                                .RecognitionRouterVoiceConfig.select(context, delegate);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("STR010_CONTROLLER_SHAPE");
        result.assertFailureContains("STR010_CONTROLLER_DEPENDENCY");
        result.assertFailureContains("STR010_ROUTE_BINDING");
        result.assertFailureContains("STR010_LIFECYCLE");
        result.assertFailureContains("STR010_REDACTION");
        result.assertFailureContains("STR010_FEATURE_FLAG");
        result.assertFailureContains("STR010_CONTROLLER_SCOPE");
        result.assertFailureContains("STR010_FLAG_SCOPE");
        result.assertFailureContains("STR010_CONTROLLER_CALLER");
        result.assertFailureContains("STR010_FLAG_CALLER");
        result.assertFailureContains("STR010_CONFIRMATION_BYPASS");
    }

    @Test
    public void rec003RejectsUnboundedOverwritingLockedAndLeakyProviderRegistries()
            throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.recognition.ProviderRegistry",
                        """
                        package com.opentypeless.android.recognition;
                        public class ProviderRegistry implements java.io.Serializable {
                            private final java.util.Map<String, Object> entries =
                                    new java.util.HashMap<>();
                            private java.net.URL endpoint;

                            public void register(String providerId, Object probe) {
                                entries.put(providerId, probe);
                            }

                            public synchronized Object probe(String providerId) {
                                return entries.get(providerId);
                            }

                            @Override public String toString() {
                                return entries.values().toString() + endpoint;
                            }

                            public interface ProviderProbe { Object probe(); }
                            public interface ProbeResult {}
                        }
                        """));

        GateResult result = audit(project);
        result.assertFailureContains("REC003_DOMAIN_DEPENDENCY");
        result.assertFailureContains("REC003_REGISTRY_SHAPE");
        result.assertFailureContains("REC003_REGISTRATION_BOUND");
        result.assertFailureContains("REC003_PROBE_LEASE");
        result.assertFailureContains("REC003_RESULT_SHAPE");
        result.assertFailureContains("REC003_REDACTION");
    }

    @Test
    public void rec004RejectsOpenLeakyUnboundedAndUnmarshalledSystemAdapters()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;
                                public interface RecognitionProvider<R> {
                                    interface EventSink {}
                                    interface Session {}
                                    interface PreparationResult {}
                                    record Prepared(String descriptor)
                                            implements PreparationResult {}
                                    record NotPrepared(String rawError)
                                            implements PreparationResult {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.AndroidSystemRecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;
                                public class AndroidSystemRecognitionProvider
                                        implements RecognitionProvider<Object> {
                                    public java.net.URL endpoint;
                                    public String transcript;

                                    public record StartRequest(
                                            String rawPrompt,
                                            java.net.URL endpoint) {}

                                    public interface Backend {
                                        interface Callback {}
                                    }

                                    public interface MainThread {}
                                    public static class SessionState {
                                        public String transcript;
                                        @Override public String toString() {
                                            return transcript;
                                        }
                                    }
                                    public static class HandlerMainThread {}
                                    public static class SystemBackend {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.EvilProvider",
                                """
                                package com.opentypeless.android.recognition;
                                final class EvilProvider implements RecognitionProvider<Object> {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.SystemSpeechRecognizer",
                                """
                                package com.opentypeless.android.recognition;
                                public class SystemSpeechRecognizer {
                                    public interface Callback {
                                        void onError(int code, String rawMessage);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.SystemRecognitionIntentFactory",
                                """
                                package com.opentypeless.android.recognition;
                                public class SystemRecognitionIntentFactory {
                                    public java.net.URL endpoint;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC004_PROVIDER_CONTRACT");
        result.assertFailureContains("REC004_ADAPTER_SHAPE");
        result.assertFailureContains("REC004_ADAPTER_DEPENDENCY");
        result.assertFailureContains("REC004_LEAST_AUTHORITY_REQUEST");
        result.assertFailureContains("REC004_FAILURE_REDACTION");
        result.assertFailureContains("REC004_MAIN_THREAD_LIFECYCLE");
        result.assertFailureContains("REC004_EVENT_TERMINAL");
        result.assertFailureContains("REC004_SYSTEM_BRIDGE");
        result.assertFailureContains("REC004_PROVIDER_IMPLEMENTATION");
    }

    @Test
    public void rec005RejectsLeakyUnboundedUploadAdaptersAndUnauthorizedCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;
                                public interface RecognitionProvider<R> {}
                                """),
                        source(
                                "com.opentypeless.android.config.ProviderConfig",
                                """
                                package com.opentypeless.android.config;
                                public final class ProviderConfig {
                                    public static final class Asr {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.SecretRef",
                                """
                                package com.opentypeless.android.config;
                                public final class SecretRef {}
                                """),
                        source(
                                "com.opentypeless.android.net.OpenAiCompatibleClient",
                                """
                                package com.opentypeless.android.net;
                                public final class OpenAiCompatibleClient {
                                    public static final int MAX_AUDIO_BYTES = Integer.MAX_VALUE;
                                    public static final int MAX_RESPONSE_BYTES = Integer.MAX_VALUE;

                                    public String transcribe(
                                            byte[] audio,
                                            String endpoint,
                                            char[] credential,
                                            String model,
                                            String language,
                                            String prompt,
                                            java.util.function.BooleanSupplier cancelled)
                                            throws Exception {
                                        java.net.HttpURLConnection connection =
                                            (java.net.HttpURLConnection)
                                                new java.net.URL(endpoint).openConnection();
                                        connection.setInstanceFollowRedirects(true);
                                        return new String(credential) + prompt;
                                    }

                                    public void cancelActiveRequest() {}

                                    public enum RequestFailure { BAD }

                                    public static final class RequestException
                                            extends java.io.IOException {
                                        public final String rawBody;
                                        public RequestException(String rawBody) {
                                            super(rawBody);
                                            this.rawBody = rawBody;
                                        }
                                        @Override public String toString() {
                                            return getMessage();
                                        }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.OpenAiCompatibleUploadProvider",
                                """
                                package com.opentypeless.android.recognition;

                                public class OpenAiCompatibleUploadProvider
                                        implements RecognitionProvider<Object> {
                                    public char[] rawCredential;
                                    public java.io.File plaintextCache;

                                    public static class StartRequest {
                                        public byte[] wav;
                                        public String audio() { return new String(wav); }
                                    }

                                    public interface UploadBackend {
                                        String upload(byte[] audio) throws Exception;
                                    }

                                    public interface Worker {
                                        void execute(Runnable action);
                                        void close();
                                    }

                                    public interface CredentialOperation {
                                        String apply(char[] credential) throws Exception;
                                    }

                                    public interface CredentialAccess {
                                        String use(
                                            com.opentypeless.android.config.SecretRef reference,
                                            CredentialOperation operation) throws Exception;
                                    }

                                    public static class CredentialUnavailableException
                                            extends Exception {
                                        public String rawMessage;
                                        @Override public String toString() { return rawMessage; }
                                    }

                                    public static class ClientUploadBackend {
                                        public String rawKey;
                                        public String bypass(
                                                com.opentypeless.android.net.OpenAiCompatibleClient client,
                                                byte[] audio,
                                                char[] key) throws Exception {
                                            return client.transcribe(
                                                audio,
                                                "http://example.invalid",
                                                key,
                                                "model",
                                                "en",
                                                "prompt",
                                                () -> false);
                                        }
                                    }

                                    public static class SingleWorker {
                                        public final java.util.concurrent.ExecutorService executor =
                                            java.util.concurrent.Executors.newCachedThreadPool();
                                    }

                                    public static class AudioClaim {
                                        public byte[] audio;
                                    }

                                    public static class SessionState {
                                        public String transcript;
                                        @Override public String toString() { return transcript; }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.UploadAuthorityLeak",
                                """
                                package com.opentypeless.android.provider;
                                public final class UploadAuthorityLeak
                                        implements com.opentypeless.android.recognition
                                            .RecognitionProvider<Object> {
                                    public com.opentypeless.android.recognition
                                        .OpenAiCompatibleUploadProvider.StartRequest request;

                                    public String steal(
                                            com.opentypeless.android.recognition
                                                .OpenAiCompatibleUploadProvider.CredentialAccess access,
                                            com.opentypeless.android.config.SecretRef ref,
                                            com.opentypeless.android.net.OpenAiCompatibleClient client)
                                            throws Exception {
                                        access.use(ref, credential -> new String(credential));
                                        return client.transcribe(
                                            new byte[] {1},
                                            "http://example.invalid",
                                            new char[] {'k'},
                                            "model",
                                            "en",
                                            "prompt",
                                            () -> false);
                                    }

                                    public void fanOut(
                                            com.opentypeless.android.recognition
                                                .OpenAiCompatibleUploadProvider.Worker worker) {
                                        worker.execute(() -> {});
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC005_ADAPTER_SHAPE");
        result.assertFailureContains("REC005_ADAPTER_DEPENDENCY");
        result.assertFailureContains("REC005_CAPABILITY_STORAGE");
        result.assertFailureContains("REC005_REQUEST_BOUND");
        result.assertFailureContains("REC005_BACKEND_SHAPE");
        result.assertFailureContains("REC005_WORKER_BOUND");
        result.assertFailureContains("REC005_CREDENTIAL_BOUNDARY");
        result.assertFailureContains("REC005_FAILURE_REDACTION");
        result.assertFailureContains("REC005_CLIENT_BINDING");
        result.assertFailureContains("REC005_SESSION_CLEANUP");
        result.assertFailureContains("REC005_EVENT_TERMINAL");
        result.assertFailureContains("REC005_LIFECYCLE");
        result.assertFailureContains("REC005_FAILURE_MAPPING");
        result.assertFailureContains("REC005_CLIENT_CONTRACT");
        result.assertFailureContains("REC005_ADAPTER_SCOPE");
        result.assertFailureContains("REC005_CLIENT_CALLER");
        result.assertFailureContains("REC005_CREDENTIAL_CALLER");
        result.assertFailureContains("REC005_ADAPTER_CALLER");
        result.assertFailureContains("REC004_PROVIDER_IMPLEMENTATION");
    }

    @Test
    public void rec006RejectsLeakyUnboundedLocalAdaptersAndUnauthorizedCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;
                                public interface RecognitionProvider<R> {}
                                """),
                        source(
                                "com.opentypeless.android.offline.LocalOfflineRecognizer",
                                """
                                package com.opentypeless.android.offline;
                                public final class LocalOfflineRecognizer {
                                    public enum DeviceSupport { SUPPORTED, UNKNOWN }
                                    public static DeviceSupport deviceSupport(
                                            android.content.Context context) {
                                        return DeviceSupport.SUPPORTED;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.offline.LocalOfflineRecognitionClient",
                                """
                                package com.opentypeless.android.offline;
                                public final class LocalOfflineRecognitionClient {
                                    public LocalOfflineRecognitionClient(
                                        android.content.Context context) {}
                                    public Result recognize(byte[] audio, String language, boolean itn) {
                                        return new Result(new String(audio), language);
                                    }
                                    public void cancelActive() {}
                                    public void close() {}
                                    public record Result(String exactText, String punctuatedText) {
                                        @Override public String toString() {
                                            return exactText + punctuatedText;
                                        }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.SenseVoiceFinalProvider",
                                """
                                package com.opentypeless.android.recognition;
                                public class SenseVoiceFinalProvider
                                        implements RecognitionProvider<Object>, java.io.Serializable {
                                    public byte[] rawAudio;
                                    public java.io.File modelPath;
                                    public Throwable nativeFailure;

                                    public static class StartRequest {
                                        public byte[] audio;
                                        public byte[] audio() { return audio; }
                                    }

                                    public enum Availability { READY, UNKNOWN }

                                    public interface Backend {
                                        String transcribe(byte[] audio);
                                    }

                                    public interface Worker {
                                        void execute(Runnable action);
                                        void close();
                                    }

                                    public static class ClientBackend implements Backend {
                                        public android.content.Context context;
                                        public com.opentypeless.android.offline
                                            .LocalOfflineRecognitionClient client;
                                        @Override public String transcribe(byte[] audio) {
                                            return client.recognize(audio, "secret", true).toString();
                                        }
                                    }

                                    public static class SingleWorker implements Worker {
                                        public final java.util.concurrent.ExecutorService executor =
                                            java.util.concurrent.Executors.newCachedThreadPool();
                                        @Override public void execute(Runnable action) {
                                            executor.execute(action);
                                        }
                                        @Override public void close() {}
                                    }

                                    public static class AudioClaim {
                                        public byte[] audio;
                                    }

                                    public static class SessionState {
                                        public String transcript;
                                        @Override public String toString() { return transcript; }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.LocalAuthorityLeak",
                                """
                                package com.opentypeless.android.provider;
                                public final class LocalAuthorityLeak {
                                    public com.opentypeless.android.recognition
                                        .SenseVoiceFinalProvider.Backend backend;
                                    public void fanOut(
                                        com.opentypeless.android.recognition
                                            .SenseVoiceFinalProvider.Worker worker) {
                                        worker.execute(() -> {});
                                    }
                                    public Object probe(android.content.Context context) {
                                        return com.opentypeless.android.offline
                                            .LocalOfflineRecognizer.deviceSupport(context);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC006_ADAPTER_SHAPE");
        result.assertFailureContains("REC006_ADAPTER_DEPENDENCY");
        result.assertFailureContains("REC006_CAPABILITY_STORAGE");
        result.assertFailureContains("REC006_REQUEST_BOUND");
        result.assertFailureContains("REC006_AVAILABILITY_MAPPING");
        result.assertFailureContains("REC006_BACKEND_SHAPE");
        result.assertFailureContains("REC006_WORKER_BOUND");
        result.assertFailureContains("REC006_CLIENT_BINDING");
        result.assertFailureContains("REC006_SESSION_CLEANUP");
        result.assertFailureContains("REC006_EVENT_TERMINAL");
        result.assertFailureContains("REC006_LIFECYCLE");
        result.assertFailureContains("REC006_DEVICE_SUPPORT");
        result.assertFailureContains("REC006_CLIENT_RESULT");
        result.assertFailureContains("REC006_ADAPTER_SCOPE");
        result.assertFailureContains("REC006_ADAPTER_CALLER");
        result.assertFailureContains("REC006_DEVICE_SUPPORT_CALLER");
    }

    @Test
    public void rec007RejectsStreamingClaimsLeakyUnboundedAndMultiWorkerPrefixAdapters()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.recognition.ProviderCapabilities",
                                """
                                package com.opentypeless.android.recognition;
                                public class ProviderCapabilities {
                                    public String transcript;
                                    public enum AudioFormat { PCM_16_MONO_16000_HZ }
                                    public enum ImplementationKind {
                                        BATCH_FINAL, PREFIX_REPLAY, TRUE_STREAMING
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.PrefixReplayPreviewProvider",
                                """
                                package com.opentypeless.android.recognition;
                                public class PrefixReplayPreviewProvider
                                        implements java.io.Serializable {
                                    public byte[] retainedAudio;
                                    public Throwable backendFailure;
                                    public java.net.URL endpoint;
                                    public java.util.concurrent.ExecutorService workers =
                                        java.util.concurrent.Executors.newCachedThreadPool();

                                    public static class StartRequest {
                                        public byte[] audio;
                                        public String language;
                                    }
                                    public interface PreviewSession {
                                        void acceptPcm(byte[] audio, int length);
                                    }
                                    public enum Availability { READY, UNKNOWN }
                                    public interface PartialSink { void onPartial(String text); }
                                    public interface PreviewEngine {
                                        void accept(byte[] audio, int length);
                                        void cancel();
                                    }
                                    public interface Backend {
                                        Availability availability();
                                        PreviewEngine open(String language, PartialSink sink);
                                        void close();
                                    }
                                    public static class SessionState {
                                        public byte[] audio;
                                        public String transcript;
                                    }
                                    public static class RequestClaim {
                                        public String language;
                                        @Override public String toString() { return language; }
                                    }
                                    public static class LocalPreviewBackend implements Backend {
                                        public java.net.URL endpoint;
                                        public Availability availability() { return Availability.READY; }
                                        public PreviewEngine open(String language, PartialSink sink) {
                                            sink.onPartial(language);
                                            return null;
                                        }
                                        public void close() {}
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.offline.LocalRealtimePreview",
                                """
                                package com.opentypeless.android.offline;
                                public class LocalRealtimePreview {
                                    public byte[] audio;
                                    public java.util.concurrent.ExecutorService workers =
                                        java.util.concurrent.Executors.newCachedThreadPool();
                                    public LocalRealtimePreview() {}
                                    public interface Decoder { String decode(byte[] audio); }
                                    public interface Listener { void onPartial(String text); }
                                    public static class LazySessionDecoder implements Decoder {
                                        public byte[] audio;
                                        public String decode(byte[] value) { return new String(value); }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.PrefixAuthorityLeak",
                                """
                                package com.opentypeless.android.provider;
                                public final class PrefixAuthorityLeak {
                                    public com.opentypeless.android.recognition
                                        .PrefixReplayPreviewProvider.Backend backend;
                                    public void invoke(
                                        com.opentypeless.android.recognition
                                            .PrefixReplayPreviewProvider.Backend value) {
                                        value.close();
                                    }
                                    public Object construct() {
                                        return new com.opentypeless.android.offline
                                            .LocalRealtimePreview();
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC001_IMPLEMENTATION_KIND_SHAPE");
        result.assertFailureContains("REC007_ADAPTER_SHAPE");
        result.assertFailureContains("REC007_ADAPTER_DEPENDENCY");
        result.assertFailureContains("REC007_CAPABILITY_STORAGE");
        result.assertFailureContains("REC007_REQUEST_BOUND");
        result.assertFailureContains("REC007_AVAILABILITY_MAPPING");
        result.assertFailureContains("REC007_BACKEND_SHAPE");
        result.assertFailureContains("REC007_SESSION_SHAPE");
        result.assertFailureContains("REC007_BACKEND_BINDING");
        result.assertFailureContains("REC007_EVENT_CONTRACT");
        result.assertFailureContains("REC007_LIFECYCLE");
        result.assertFailureContains("REC007_PREVIEW_BOUND");
        result.assertFailureContains("REC007_ADAPTER_SCOPE");
        result.assertFailureContains("REC007_ADAPTER_CALLER");
        result.assertFailureContains("REC007_PREVIEW_CALLER");
    }

    @Test
    public void rec008RejectsLeakySplitFailureMappersAndUnauthorizedCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.config.RecognitionRoute",
                                """
                                package com.opentypeless.android.config;
                                public final class RecognitionRoute {
                                    public enum FailureClass {
                                        UNAVAILABLE, MODEL_MISSING, PERMISSION_DENIED,
                                        OEM_MIC_BLOCKED, AUDIO_ERROR, NETWORK_UNAVAILABLE,
                                        NETWORK_TIMEOUT, AUTHENTICATION, QUOTA_EXCEEDED,
                                        RATE_LIMITED, SERVER_ERROR, PROTOCOL_ERROR,
                                        RECOGNIZER_BUSY, NO_MATCH, SPEECH_TIMEOUT,
                                        UNSUPPORTED_LANGUAGE, CANCELLED, TARGET_CHANGED,
                                        INTERNAL_ERROR
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionFailureMapper",
                                """
                                package com.opentypeless.android.recognition;
                                public final class RecognitionFailureMapper {
                                    public enum LocalAvailability { READY, UNKNOWN }
                                    public Throwable retained;
                                    public static Object fromUpload(Throwable error) {
                                        System.out.println(error.getMessage());
                                        return error;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionFailure",
                                """
                                package com.opentypeless.android.recognition;
                                public record RecognitionFailure(int errorCode, String message) {
                                    @Override public String toString() { return message; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.AndroidSystemRecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;
                                final class AndroidSystemRecognitionProvider {
                                    static Object failureClass(int code, String raw) {
                                        return raw;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.LeakyFailureConsumer",
                                """
                                package com.opentypeless.android.provider;
                                public final class LeakyFailureConsumer {
                                    public Object classify(Throwable error) {
                                        return com.opentypeless.android.recognition
                                                .RecognitionFailureMapper.fromUpload(error);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC008_MAPPER_SHAPE");
        result.assertFailureContains("REC008_FAILURE_REDACTION");
        result.assertFailureContains("REC008_LOCAL_AVAILABILITY_SHAPE");
        result.assertFailureContains("REC008_LEGACY_FAILURE_SHAPE");
        result.assertFailureContains("REC008_MAPPER_SCOPE");
        result.assertFailureContains("REC008_MAPPER_CALLER");
        result.assertFailureContains("REC008_PROVIDER_DELEGATION");
    }

    @Test
    public void rec009RejectsExecutableRouterOpenTokensAndRegistryLeaseOutsiders()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.recognition.RecognitionProvider",
                                """
                                package com.opentypeless.android.recognition;
                                public interface RecognitionProvider<T> { T execute(); }
                                """),
                        source(
                                "com.opentypeless.android.config.EffectiveProfile",
                                """
                                package com.opentypeless.android.config;
                                public final class EffectiveProfile {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.ProviderRegistry",
                                """
                                package com.opentypeless.android.recognition;
                                public class ProviderRegistry {
                                    public static class RouteLease {}
                                    public RouteLease routeLease(String providerId) {
                                        return new RouteLease();
                                    }
                                    public boolean isCurrent(RouteLease lease) { return true; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionRouter",
                                """
                                package com.opentypeless.android.recognition;
                                public class RecognitionRouter implements java.io.Serializable {
                                    public static final class PrivacyAuthorization {
                                        public com.opentypeless.android.config.EffectiveProfile ownerProfile;
                                        @Override public String toString() {
                                            return ownerProfile.toString();
                                        }
                                    }
                                    public enum ConfirmationDecision { APPROVE_ONCE, CANCEL }
                                    public static final class ConfirmationRequest {}
                                    public String transcript;
                                    public RecognitionProvider<String> provider;
                                    public com.opentypeless.android.config.EffectiveProfile effectiveProfile;
                                    public PrivacyAuthorization privacyAuthorization;
                                    public Object start() {
                                        System.out.println(transcript);
                                        return provider.execute();
                                    }
                                    public Object onConfirmation(
                                            ConfirmationRequest request,
                                            ConfirmationDecision decision) {
                                        return request;
                                    }
                                    @Override public String toString() {
                                        return transcript + provider + effectiveProfile
                                                + privacyAuthorization;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.LeakyRouterConsumer",
                                """
                                package com.opentypeless.android.provider;
                                public final class LeakyRouterConsumer {
                                    public com.opentypeless.android.recognition.RecognitionRouter router;
                                    public com.opentypeless.android.recognition.RecognitionRouter
                                            .PrivacyAuthorization authorization;
                                    public com.opentypeless.android.recognition.ProviderRegistry.RouteLease lease;
                                    public Object acquire(
                                            com.opentypeless.android.recognition.ProviderRegistry registry) {
                                        return registry.routeLease("secret.provider");
                                    }
                                    public Object confirm(
                                            com.opentypeless.android.recognition.RecognitionRouter router,
                                            com.opentypeless.android.recognition.RecognitionRouter
                                                    .ConfirmationRequest request) {
                                        return router.onConfirmation(
                                                request,
                                                com.opentypeless.android.recognition.RecognitionRouter
                                                        .ConfirmationDecision.CANCEL);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC009_ROUTER_DEPENDENCY");
        result.assertFailureContains("REC009_ROUTER_SHAPE");
        result.assertFailureContains("REC009_ROUTE_POLICY");
        result.assertFailureContains("REC009_ROUTER_REDACTION");
        result.assertFailureContains("REC009_REGISTRY_LEASE");
        result.assertFailureContains("REC009_ROUTER_SCOPE");
        result.assertFailureContains("REC009_LEASE_SCOPE");
        result.assertFailureContains("REC009_LEASE_CALLER");
        result.assertFailureContains("REC010_CONFIRMATION_SHAPE");
        result.assertFailureContains("REC010_CONFIRMATION_POLICY");
        result.assertFailureContains("REC010_CONFIRMATION_REDACTION");
        result.assertFailureContains("REC010_CONFIRMATION_SCOPE");
        result.assertFailureContains("REC010_CONFIRMATION_CALLER");
    }

    @Test
    public void rec011RejectsOpenLeakyBreakerReusablePermitsAndOutsideCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.config.RecognitionRoute",
                                """
                                package com.opentypeless.android.config;
                                public final class RecognitionRoute {
                                    public enum FailureClass {
                                        UNAVAILABLE, MODEL_MISSING, PERMISSION_DENIED,
                                        OEM_MIC_BLOCKED, AUDIO_ERROR, NETWORK_UNAVAILABLE,
                                        NETWORK_TIMEOUT, AUTHENTICATION, QUOTA_EXCEEDED,
                                        RATE_LIMITED, SERVER_ERROR, PROTOCOL_ERROR,
                                        RECOGNIZER_BUSY, NO_MATCH, SPEECH_TIMEOUT,
                                        UNSUPPORTED_LANGUAGE, CANCELLED, TARGET_CHANGED,
                                        INTERNAL_ERROR
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.ProviderDescriptor",
                                """
                                package com.opentypeless.android.recognition;
                                public record ProviderDescriptor(String id, String displayName) {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.ProviderRegistry",
                                """
                                package com.opentypeless.android.recognition;
                                final class ProviderRegistry {
                                    static final int MAX_PROVIDERS = 32;
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.ProviderCircuitBreaker",
                                """
                                package com.opentypeless.android.recognition;
                                public class ProviderCircuitBreaker
                                        implements java.io.Serializable {
                                    public interface MonotonicClock { long nowMillis(); }
                                    public interface AcquireResult {}
                                    public static final class Permit {
                                        public ProviderDescriptor descriptor;
                                        public boolean consumed;
                                        public Permit(ProviderDescriptor descriptor) {
                                            this.descriptor = descriptor;
                                        }
                                        @Override public String toString() {
                                            return descriptor.id();
                                        }
                                    }
                                    public record PermitGranted(Permit permit)
                                            implements AcquireResult {}
                                    public record PermitRejected(RejectionReason reason)
                                            implements AcquireResult {}
                                    public enum RejectionReason { OPEN }
                                    public enum Disposition { RECORDED }
                                    public enum State { CLOSED }
                                    public static final class Entry {
                                        public ProviderDescriptor descriptor;
                                    }
                                    public AcquireResult acquire(ProviderDescriptor descriptor) {
                                        return new PermitGranted(new Permit(descriptor));
                                    }
                                    public Disposition onSuccess(Permit permit) {
                                        return Disposition.RECORDED;
                                    }
                                    public Disposition onFailure(
                                            Permit permit,
                                            com.opentypeless.android.config.RecognitionRoute
                                                    .FailureClass failure) {
                                        return Disposition.RECORDED;
                                    }
                                    public Disposition abandon(Permit permit) {
                                        return Disposition.RECORDED;
                                    }
                                    @Override public String toString() { return "open"; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.RecognitionRouter",
                                """
                                package com.opentypeless.android.recognition;
                                final class RecognitionRouter {
                                    ProviderCircuitBreaker breaker;
                                    Object start(ProviderDescriptor descriptor) {
                                        return breaker.acquire(descriptor);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.BreakerOutsider",
                                """
                                package com.opentypeless.android.recognition;
                                final class BreakerOutsider {
                                    ProviderCircuitBreaker breaker;
                                    Object bypass(ProviderDescriptor descriptor) {
                                        return breaker.acquire(descriptor);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC011_BREAKER_SHAPE");
        result.assertFailureContains("REC011_BREAKER_POLICY");
        result.assertFailureContains("REC011_BREAKER_REDACTION");
        result.assertFailureContains("REC011_PERMIT_SHAPE");
        result.assertFailureContains("REC011_ROUTER_BINDING");
        result.assertFailureContains("REC011_BREAKER_SCOPE");
        result.assertFailureContains("REC011_BREAKER_CALLER");
    }

    @Test
    public void rec012RejectsRawTerminalStateOpenCoordinatorAndOutsideCapabilityCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.recognition.SystemRecognitionSupport",
                                """
                                package com.opentypeless.android.recognition;
                                public class SystemRecognitionSupport {
                                    public enum Status { ERROR }
                                    public record Result(Status status, String language,
                                                         String message) {
                                        @Override public String toString() {
                                            return language + message;
                                        }
                                    }
                                    public static String leak(RuntimeException failure) {
                                        return failure.getMessage();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.SystemRecognitionIntentFactory",
                                """
                                package com.opentypeless.android.recognition;
                                public final class SystemRecognitionIntentFactory {
                                    public static Object createCapabilityRequest(Object settings) {
                                        return settings;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.recognition.SystemRecognitionSupportApi33",
                                """
                                package com.opentypeless.android.recognition;
                                public final class SystemRecognitionSupportApi33 {}
                                """),
                        source(
                                "com.opentypeless.android.recognition.SystemModelDownloadCoordinator",
                                """
                                package com.opentypeless.android.recognition;
                                public class SystemModelDownloadCoordinator {
                                    public String rawState;
                                    public record State(long generation, String language) {}
                                    public State snapshot() {
                                        return new State(1L, rawState);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.SupportBypass",
                                """
                                package com.opentypeless.android.provider;
                                public final class SupportBypass {
                                    public com.opentypeless.android.recognition
                                            .SystemRecognitionSupport.Result result;
                                    public Object capability() {
                                        return com.opentypeless.android.recognition
                                                .SystemRecognitionIntentFactory
                                                .createCapabilityRequest(this);
                                    }
                                    public Object observe(
                                            com.opentypeless.android.recognition
                                                    .SystemModelDownloadCoordinator coordinator) {
                                        return coordinator.snapshot();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.MainActivity",
                                """
                                package com.opentypeless.android;
                                public final class MainActivity {
                                    @Override public String toString() { return "no subscription"; }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("REC012_RESULT_SHAPE");
        result.assertFailureContains("REC012_STATE_SHAPE");
        result.assertFailureContains("REC012_COORDINATOR_SHAPE");
        result.assertFailureContains("REC012_ENUM_SHAPE");
        result.assertFailureContains("REC012_RAW_ERROR");
        result.assertFailureContains("REC012_SCOPE");
        result.assertFailureContains("REC012_CAPABILITY_CALLER");
        result.assertFailureContains("REC012_COORDINATOR_CALLER");
        result.assertFailureContains("REC012_API33_BINDING");
        result.assertFailureContains("REC012_ACTIVITY_BINDING");
    }

    @Test
    public void cfg003OverrideValueRejectsCollapsedStatesOpenCodecsAndPersistenceAuthority()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.config.OverrideValue",
                                """
                                package com.opentypeless.android.config;
                                public sealed interface OverrideValue<T>
                                        extends java.io.Serializable
                                        permits OverrideValue.Inherit,
                                                OverrideValue.Disabled,
                                                OverrideValue.Value {
                                    final class Inherit<T> implements OverrideValue<T> {
                                        public String secret;
                                        public Inherit() {}
                                    }
                                    final class Disabled<T> implements OverrideValue<T> {
                                        public Disabled() {}
                                    }
                                    record Value<T>(T value, String raw)
                                            implements OverrideValue<T> {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.OverrideValueCodec",
                                """
                                package com.opentypeless.android.config;
                                public class OverrideValueCodec<T> {
                                    public java.io.File persistenceAuthority;

                                    public interface ScalarCodec<T>
                                            extends java.io.Serializable {
                                        String encode(T value);
                                    }

                                    public record DbRow(String encodedValue) {}

                                    public static class FormatException
                                            extends IllegalArgumentException {
                                        public FormatException(String message) {
                                            super(message);
                                        }
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG003_OVERRIDE_VALUE_SHAPE");
        result.assertFailureContains("CFG003_OVERRIDE_SINGLETON_SHAPE");
        result.assertFailureContains("CFG003_OVERRIDE_VALUE_RECORD_SHAPE");
        result.assertFailureContains("CFG003_CODEC_SHAPE");
        result.assertFailureContains("CFG003_SCALAR_CODEC_SHAPE");
        result.assertFailureContains("CFG003_DB_ROW_SHAPE");
        result.assertFailureContains("CFG003_FORMAT_ERROR_SHAPE");
        result.assertFailureContains("CFG003_CODEC_AUTHORITY");
        result.assertFailureContains("CFG001_DOMAIN_DEPENDENCY");
    }

    @Test
    public void cfg004PartitionsRejectNullableMapsLegacyAuthorityAndVocabularyDrift()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "com.opentypeless.android.config.ProcessingMode",
                                """
                                package com.opentypeless.android.config;
                                public enum ProcessingMode { AUTO, MAGIC, RETRY_FOREVER }
                                """),
                        source(
                                "com.opentypeless.android.settings.AppSettings",
                                """
                                package com.opentypeless.android.settings;
                                public final class AppSettings {}
                                """),
                        source(
                                "com.opentypeless.android.config.GlobalConfig",
                                """
                                package com.opentypeless.android.config;
                                public class GlobalConfig implements java.io.Serializable {
                                    public android.content.Context context;
                                    public java.util.Map<String, Object> nullableValues;
                                    public com.opentypeless.android.settings.AppSettings legacy;
                                    public String packageName;
                                    @Override public String toString() {
                                        return packageName + nullableValues;
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.RuleOverrides",
                                """
                                package com.opentypeless.android.config;
                                public record RuleOverrides(Object route, Boolean sendContext) {}
                                """),
                        source(
                                "com.opentypeless.android.config.FieldRule",
                                """
                                package com.opentypeless.android.config;
                                public record FieldRule(Object matcher, Object overrides) {
                                    public record FieldMatcher(String regex, Class<?> callback) {}
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG004_PROCESSING_MODE_SHAPE");
        result.assertFailureContains("CFG004_RECORD_SHAPE");
        result.assertFailureContains("CFG004_VALIDATION_EDGE");
        result.assertFailureContains("CFG004_AUTHORITY_BOUNDARY");
        result.assertFailureContains("CFG001_DOMAIN_DEPENDENCY");
    }

    @Test
    public void cfg005ResolverRejectsOpenShapesAuthorityAndFactoryBypasses()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.config.EffectiveProfile",
                                """
                                package com.opentypeless.android.config;
                                public record EffectiveProfile(java.util.Map<String, Object> raw) {
                                    public static Object resolved(Object value) { return value; }
                                    public enum RuleSource { PROVIDER, USER, MAGIC }
                                    public enum ResolutionExplanation { RAW_TEXT }
                                    public static class ResolvedValue {
                                        public Object value;
                                        public ResolvedValue(Object value) { this.value = value; }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.EffectiveProfileResolver",
                                """
                                package com.opentypeless.android.config;
                                public class EffectiveProfileResolver {
                                    public static final int MAX_APP_RULES = Integer.MAX_VALUE;
                                    public static final int MAX_FIELD_RULES = Integer.MAX_VALUE;
                                    public static EffectiveProfile resolve(Request request) {
                                        return new EffectiveProfile(request.values());
                                    }
                                    public record ProviderDefaults(Object raw) {}
                                    public record Request(java.util.Map<String, Object> values) {}
                                    public enum ResolutionFailure { RAW }
                                    public static class ResolutionException
                                            extends IllegalArgumentException {
                                        public final String raw;
                                        public ResolutionException(String raw) {
                                            super(raw);
                                            this.raw = raw;
                                        }
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.GlobalConfig",
                                """
                                package com.opentypeless.android.config;
                                public final class GlobalConfig {
                                    public Object forge(Object value) {
                                        return EffectiveProfile.resolved(value);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG005_EFFECTIVE_PROFILE_SHAPE");
        result.assertFailureContains("CFG005_RULE_SOURCE_SHAPE");
        result.assertFailureContains("CFG005_EXPLANATION_SHAPE");
        result.assertFailureContains("CFG005_RESOLVED_VALUE_SHAPE");
        result.assertFailureContains("CFG005_RESOLVER_BINARY_SHAPE");
        result.assertFailureContains("CFG005_INPUT_RECORD_SHAPE");
        result.assertFailureContains("CFG005_FAILURE_SHAPE");
        result.assertFailureContains("CFG005_EXCEPTION_SHAPE");
        result.assertFailureContains("CFG005_AUTHORITY_BOUNDARY");
        result.assertFailureContains("CFG005_RESOLUTION_AUTHORITY");
    }

    @Test
    public void cfg006MigrationRejectsSecretsAsyncCommitOpenShapesAndWrongCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.SharedPreferences",
                                """
                                package android.content;
                                public interface SharedPreferences {
                                    java.util.Map<String, ?> getAll();
                                    Editor edit();
                                    interface Editor {
                                        Editor putString(String key, String value);
                                        Editor putBoolean(String key, boolean value);
                                        Editor putInt(String key, int value);
                                        Editor putLong(String key, long value);
                                        Editor clear();
                                        void apply();
                                        boolean commit();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.GlobalConfig",
                                """
                                package com.opentypeless.android.config;
                                public final class GlobalConfig {}
                                """),
                        source(
                                "com.opentypeless.android.settings.AppSettings",
                                """
                                package com.opentypeless.android.settings;
                                public final class AppSettings {}
                                """),
                        source(
                                "com.opentypeless.android.settings.RecognitionBackend",
                                """
                                package com.opentypeless.android.settings;
                                public enum RecognitionBackend { OPENAI_COMPATIBLE }
                                """),
                        source(
                                "com.opentypeless.android.security.SecurePreferences",
                                """
                                package com.opentypeless.android.security;
                                public final class SecurePreferences {}
                                """),
                        source(
                                "com.opentypeless.android.settings.LegacyAppSettingsMigration",
                                """
                                package com.opentypeless.android.settings;
                                public class LegacyAppSettingsMigration {
                                    public static com.opentypeless.android.config.GlobalConfig migrate(
                                            android.content.SharedPreferences preferences,
                                            RecognitionBackend backend) { return null; }
                                    public static void writeProjection(
                                            android.content.SharedPreferences.Editor editor,
                                            AppSettings settings,
                                            long revision) { editor.apply(); }
                                    public enum MigrationFailure {
                                        MALFORMED_SOURCE, UNKNOWN_TARGET_VERSION, PARTIAL_TARGET,
                                        COMMIT_FAILED, READBACK_FAILED, SECRET
                                    }
                                    public static class MigrationException extends RuntimeException {
                                        public String raw;
                                    }
                                    public interface Store {
                                        java.util.Map<String, ?> latest();
                                        void delete();
                                    }
                                    public record Projection(
                                            com.opentypeless.android.config.GlobalConfig config,
                                            com.opentypeless.android.security.SecurePreferences secret) {}
                                    public record ExistingTarget(String raw) {}
                                    public record LegacyValues(String key) {}
                                    public static final class SharedPreferencesStore {
                                        public android.content.SharedPreferences preferences;
                                        public boolean commit() {
                                            preferences.edit().clear().apply();
                                            return true;
                                        }
                                    }
                                    public static final class StringScalarCodec {}
                                    public static final class ProcessingScalarCodec {}
                                    public static final class BooleanScalarCodec {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.settings.SettingsRepository",
                                """
                                package com.opentypeless.android.settings;
                                public final class SettingsRepository {
                                    android.content.SharedPreferences preferences;
                                    RecognitionBackend backend;
                                    public Object loadMigratedGlobalConfig() {
                                        return LegacyAppSettingsMigration.migrate(
                                                preferences, backend);
                                    }
                                    public Object bypass() {
                                        return LegacyAppSettingsMigration.migrate(
                                                preferences, backend);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG006_MIGRATION_BINARY_SHAPE");
        result.assertFailureContains("CFG006_FAILURE_SHAPE");
        result.assertFailureContains("CFG006_EXCEPTION_SHAPE");
        result.assertFailureContains("CFG006_STORE_SHAPE");
        result.assertFailureContains("CFG006_PROJECTION_SHAPE");
        result.assertFailureContains("CFG006_SHARED_PREFERENCES_ATOMICITY");
        result.assertFailureContains("CFG006_SECRET_AUTHORITY_BOUNDARY");
        result.assertFailureContains("CFG006_MIGRATION_CALLER");
        result.assertFailureContains("CFG006_REPOSITORY_EXACT_EDGE");
    }

    @Test
    public void cfg006ProductionMigrationBinariesAreRequiredInEveryVariant() throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("CFG006_BINARY_MISSING");
        result.assertFailureContains("LegacyAppSettingsMigration");
    }

    @Test
    public void cfg007RejectsOpenAsyncSecretProfileMigrationAndWrongRepositoryCallers()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.SharedPreferences",
                                """
                                package android.content;
                                public interface SharedPreferences {
                                    java.util.Map<String, ?> getAll();
                                    Editor edit();
                                    interface Editor {
                                        Editor putString(String key, String value);
                                        Editor putBoolean(String key, boolean value);
                                        Editor putInt(String key, int value);
                                        Editor clear();
                                        void apply();
                                        boolean commit();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.AppRule",
                                """
                                package com.opentypeless.android.config;
                                public final class AppRule {}
                                """),
                        source(
                                "com.opentypeless.android.settings.AppProfile",
                                """
                                package com.opentypeless.android.settings;
                                public final class AppProfile {}
                                """),
                        source(
                                "com.opentypeless.android.security.SecurePreferences",
                                """
                                package com.opentypeless.android.security;
                                public final class SecurePreferences {}
                                """),
                        source(
                                "com.opentypeless.android.settings.LegacyAppProfileMigration",
                                """
                                package com.opentypeless.android.settings;
                                public class LegacyAppProfileMigration {
                                    public com.opentypeless.android.security.SecurePreferences secret;
                                    public static java.util.List migrate(
                                            android.content.SharedPreferences preferences) {
                                        return java.util.List.of();
                                    }
                                    public enum MigrationFailure {
                                        MALFORMED_SOURCE, SOURCE_LIMIT_EXCEEDED, DUPLICATE_SOURCE,
                                        UNKNOWN_TARGET_VERSION, PARTIAL_TARGET, COMMIT_FAILED,
                                        READBACK_FAILED, RAW_PAYLOAD
                                    }
                                    public static class MigrationException extends RuntimeException {
                                        public String raw;
                                    }
                                    public interface Store {
                                        java.util.Map<String, ?> latest();
                                        void delete();
                                    }
                                    public record ExistingTarget(String raw) {}
                                    public static final class SharedPreferencesStore {
                                        public android.content.SharedPreferences preferences;
                                        public boolean commit() {
                                            preferences.edit().clear().apply();
                                            return true;
                                        }
                                    }
                                    public static final class StringScalarCodec {}
                                    public static final class ProcessingScalarCodec {}
                                    public static final class BooleanScalarCodec {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.settings.AppProfileRepository",
                                """
                                package com.opentypeless.android.settings;
                                public final class AppProfileRepository {
                                    android.content.SharedPreferences preferences;
                                    public Object loadMigratedAppRules() {
                                        return LegacyAppProfileMigration.migrate(preferences);
                                    }
                                    public Object bypass() {
                                        return LegacyAppProfileMigration.migrate(preferences);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.AppProfileMigrationShortcut",
                                """
                                package com.opentypeless.android.ime;
                                public final class AppProfileMigrationShortcut {
                                    public Object migrate(android.content.SharedPreferences preferences) {
                                        return com.opentypeless.android.settings
                                                .LegacyAppProfileMigration.migrate(preferences);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG007_MIGRATION_BINARY_SHAPE");
        result.assertFailureContains("CFG007_FAILURE_SHAPE");
        result.assertFailureContains("CFG007_EXCEPTION_SHAPE");
        result.assertFailureContains("CFG007_STORE_SHAPE");
        result.assertFailureContains("CFG007_TARGET_SHAPE");
        result.assertFailureContains("CFG007_SCALAR_CODEC_SHAPE");
        result.assertFailureContains("CFG007_SHARED_PREFERENCES_ATOMICITY");
        result.assertFailureContains("CFG007_SECRET_AUTHORITY_BOUNDARY");
        result.assertFailureContains("CFG007_MIGRATION_AUTHORITY");
        result.assertFailureContains("CFG007_MIGRATION_CALLER");
        result.assertFailureContains("CFG007_REPOSITORY_EXACT_EDGE");
    }

    @Test
    public void cfg007ProductionMigrationBinariesAreRequiredInEveryVariant() throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "release",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("CFG007_BINARY_MISSING");
        result.assertFailureContains("LegacyAppProfileMigration");
    }

    @Test
    public void cfg008RejectsPlaintextStoreShapeAndUnauthorizedBridgeCallers() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {}
                                """),
                        source(
                                "com.opentypeless.android.config.SecretRef",
                                """
                                package com.opentypeless.android.config;
                                public record SecretRef(Kind kind, String opaqueId) {
                                    public enum Kind { ASR, LLM, CONNECTOR }
                                }
                                """),
                        source(
                                "com.opentypeless.android.security.SecurePreferences",
                                """
                                package com.opentypeless.android.security;
                                public final class SecurePreferences {
                                    public SecurePreferences(android.content.Context context) {}
                                    java.util.Map<String, ?> snapshot() { return java.util.Map.of(); }
                                    String prepareStored(char[] value) { return "cipher"; }
                                    char[] decryptStored(String value) { return new char[0]; }
                                    void commitStored(
                                        java.util.Map<String, String> values,
                                        java.util.Set<String> removals) {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.security.SecretStore",
                                """
                                package com.opentypeless.android.security;
                                import com.opentypeless.android.config.SecretRef;
                                public class SecretStore implements java.io.Serializable {
                                    public String plaintext;
                                    public SecretStore(android.content.Context context) {}
                                    public SecretRef create(SecretRef.Kind kind, char[] value) {
                                        return new SecretRef(kind, "sec_bad");
                                    }
                                    public SecretRef rotate(SecretRef ref, char[] value) { return ref; }
                                    public boolean delete(SecretRef ref) { return true; }
                                    public void use(SecretRef ref, SecretUse use) { use.accept(value()); }
                                    public LegacyRefs migrateLegacy() { return new LegacyRefs("secret"); }
                                    public LegacyRefs commitLegacyPrepared(java.util.Map values) {
                                        return migrateLegacy();
                                    }
                                    public String storedLegacyValue(LegacySlot slot) { return plaintext; }
                                    public String getString() { return plaintext; }
                                    private char[] value() { return plaintext.toCharArray(); }
                                    public interface SecretUse { char[] accept(char[] secret); }
                                    public interface IdSource { String latest(); }
                                    public interface Storage {
                                        java.util.Map latest();
                                        String protect(String plaintext);
                                    }
                                    public static final class SecureStorage implements Storage {
                                        public SecurePreferences preferences;
                                        public java.util.Map latest() { return preferences.snapshot(); }
                                        public String protect(String plaintext) { return plaintext; }
                                    }
                                    public enum LegacySlot { STT_ASR, STREAMING_ASR, LLM, RAW }
                                    public record LegacyRefs(String raw) {}
                                    public enum Failure { INVALID_INPUT, RAW_SECRET }
                                    public static class SecretStoreException extends RuntimeException {
                                        public String raw;
                                    }
                                    public record StoreState(String raw) {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.security.SecretStoreBackdoor",
                                """
                                package com.opentypeless.android.security;
                                public final class SecretStoreBackdoor {
                                    public Object steal(SecurePreferences preferences) {
                                        return preferences.snapshot();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.settings.AppSettings",
                                """
                                package com.opentypeless.android.settings;
                                public final class AppSettings {}
                                """),
                        source(
                                "com.opentypeless.android.settings.SettingsRepository",
                                """
                                package com.opentypeless.android.settings;
                                import com.opentypeless.android.security.SecretStore;
                                public final class SettingsRepository {
                                    public SecretStore secretStore;
                                    public SettingsRepository(android.content.Context context) {
                                        secretStore = new SecretStore(context);
                                    }
                                    public AppSettings load() { return new AppSettings(); }
                                    public SecretStore.LegacyRefs loadMigratedSecretRefs() {
                                        return secretStore.migrateLegacy();
                                    }
                                    public String bypass() {
                                        secretStore.commitLegacyPrepared(java.util.Map.of());
                                        return secretStore.storedLegacyValue(SecretStore.LegacySlot.STT_ASR);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.ime.SecretStoreShortcut",
                                """
                                package com.opentypeless.android.ime;
                                public final class SecretStoreShortcut {
                                    public String leak(
                                        com.opentypeless.android.security.SecretStore store) {
                                        store.migrateLegacy();
                                        return store.storedLegacyValue(
                                            com.opentypeless.android.security.SecretStore
                                                .LegacySlot.STT_ASR);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG008_SECRET_STORE_SHAPE");
        result.assertFailureContains("CFG008_USE_SHAPE");
        result.assertFailureContains("CFG008_ID_SOURCE_SHAPE");
        result.assertFailureContains("CFG008_STORAGE_SHAPE");
        result.assertFailureContains("CFG008_SECURE_STORAGE_SHAPE");
        result.assertFailureContains("CFG008_LEGACY_SLOT_SHAPE");
        result.assertFailureContains("CFG008_LEGACY_REFS_SHAPE");
        result.assertFailureContains("CFG008_FAILURE_SHAPE");
        result.assertFailureContains("CFG008_EXCEPTION_SHAPE");
        result.assertFailureContains("CFG008_STATE_SHAPE");
        result.assertFailureContains("CFG008_SECRET_DEPENDENCY");
        result.assertFailureContains("CFG008_SECRET_AUTHORITY");
        result.assertFailureContains("CFG008_SECURE_PREFERENCES_CALLER");
        result.assertFailureContains("CFG008_LEGACY_BRIDGE_CALLER");
        result.assertFailureContains("CFG008_SECRET_STORE_CALLER");
        result.assertFailureContains("CFG008_REPOSITORY_EXACT_EDGE");
    }

    @Test
    public void cfg008ProductionSecretStoreBinariesAreRequiredInEveryVariant() throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("CFG008_BINARY_MISSING");
        result.assertFailureContains("SecretStore");
        result.assertFailureContains("SecurePreferences");
    }

    @Test
    public void cfg009RejectsBroadInventoryOpenShapesAndUnauthorizedCallers() throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "android.content.pm.PackageManager",
                                """
                                package android.content.pm;
                                public class PackageManager {
                                    public java.util.List getInstalledPackages(int flags) {
                                        return java.util.List.of();
                                    }
                                }
                                """),
                        source(
                                "android.content.pm.LauncherApps",
                                """
                                package android.content.pm;
                                public class LauncherApps {
                                    public java.util.List getProfiles() { return java.util.List.of(); }
                                }
                                """),
                        source(
                                "android.content.Context",
                                """
                                package android.content;
                                public class Context {
                                    public android.content.pm.PackageManager getPackageManager() {
                                        return new android.content.pm.PackageManager();
                                    }
                                    public <T> T getSystemService(Class<T> type) { return null; }
                                }
                                """),
                        source(
                                "android.app.Activity",
                                """
                                package android.app;
                                public class Activity extends android.content.Context {}
                                """),
                        source(
                                "android.app.AlertDialog",
                                """
                                package android.app;
                                public class AlertDialog {}
                                """),
                        source(
                                "com.opentypeless.android.config.AppPickerModel",
                                """
                                package com.opentypeless.android.config;
                                public class AppPickerModel implements java.io.Serializable {
                                    public java.util.List entries;
                                    public AppPickerModel(java.util.List values) { entries = values; }
                                    public java.util.List entries() { return entries; }
                                    public java.util.List search(String raw) { return entries; }
                                    public record Entry(
                                        String label,
                                        String packageName,
                                        android.content.Context context) {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.InstalledAppCatalog",
                                """
                                package com.opentypeless.android;
                                public final class InstalledAppCatalog {
                                    public static Snapshot load(android.content.Context context) {
                                        context.getPackageManager().getInstalledPackages(0);
                                        android.content.pm.LauncherApps apps = context.getSystemService(
                                            android.content.pm.LauncherApps.class);
                                        apps.getProfiles();
                                        return new Snapshot(null, null);
                                    }
                                    public record Snapshot(
                                        com.opentypeless.android.config.AppPickerModel model,
                                        java.util.Map icons) {}
                                    public static class CatalogUnavailableException
                                            extends RuntimeException {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.AppPickerDialog",
                                """
                                package com.opentypeless.android;
                                public final class AppPickerDialog {
                                    public static android.app.AlertDialog show(
                                            android.app.Activity activity,
                                            Listener listener) {
                                        InstalledAppCatalog.load(activity);
                                        return null;
                                    }
                                    public interface Listener {
                                        void onAppSelected(
                                            com.opentypeless.android.config.AppPickerModel.Entry entry);
                                        void onAdvancedPackageRequested();
                                    }
                                    public static class AppAdapter {}
                                    public static class Row {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.AppProfileActivity",
                                """
                                package com.opentypeless.android;
                                public class AppProfileActivity extends android.app.Activity {
                                    public void open(AppPickerDialog.Listener listener) {
                                        AppPickerDialog.show(this, listener);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.net.AppInventoryUpload",
                                """
                                package com.opentypeless.android.net;
                                public final class AppInventoryUpload {
                                    public Object upload(
                                        android.content.Context context,
                                        com.opentypeless.android.config.AppPickerModel model) {
                                        return com.opentypeless.android.InstalledAppCatalog.load(context);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG009_MODEL_SHAPE");
        result.assertFailureContains("CFG009_ENTRY_SHAPE");
        result.assertFailureContains("CFG009_CATALOG_SHAPE");
        result.assertFailureContains("CFG009_SNAPSHOT_SHAPE");
        result.assertFailureContains("CFG009_FAILURE_SHAPE");
        result.assertFailureContains("CFG009_DIALOG_SHAPE");
        result.assertFailureContains("CFG009_LISTENER_SHAPE");
        result.assertFailureContains("CFG009_ADAPTER_SHAPE");
        result.assertFailureContains("CFG009_ROW_SHAPE");
        result.assertFailureContains("CFG009_ACTIVITY_EXACT_EDGE");
        result.assertFailureContains("CFG009_BROAD_PACKAGE_VISIBILITY");
        result.assertFailureContains("CFG009_LAUNCHER_APPS_CALLER");
        result.assertFailureContains("CFG009_CATALOG_AUTHORITY");
        result.assertFailureContains("CFG009_CATALOG_CALLER");
        result.assertFailureContains("CFG009_DIALOG_CALLER");
        result.assertFailureContains("CFG009_INVENTORY_EXFILTRATION");
    }

    @Test
    public void cfg009ProductionPickerBinariesAreRequiredInEveryVariant() throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "release",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("CFG009_BINARY_MISSING");
        result.assertFailureContains("AppPickerModel");
        result.assertFailureContains("InstalledAppCatalog");
        result.assertFailureContains("AppPickerDialog");
        result.assertFailureContains("AppProfileActivity");
    }

    @Test
    public void cfg010RejectsOpenExplanationValuesPriorityRecomputationAndVocabularyLeaks()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.config.ProcessingMode",
                                """
                                package com.opentypeless.android.config;
                                public enum ProcessingMode { AUTO, EXACT }
                                """),
                        source(
                                "com.opentypeless.android.config.EffectiveProfile",
                                """
                                package com.opentypeless.android.config;
                                public final class EffectiveProfile {
                                    public enum RuleSource {
                                        HARD_SAFETY, SESSION, FIELD, APPLICATION, GLOBAL,
                                        PROVIDER_DEFAULT
                                    }
                                    public enum ResolutionExplanation {
                                        HARD_SENSITIVE_FIELD, REQUIRED_GLOBAL_VALUE,
                                        EXPLICIT_VALUE, EXPLICIT_DISABLED
                                    }
                                    public static final class ResolvedValue<T> {
                                        public T value() { return null; }
                                        public RuleSource source() { return RuleSource.GLOBAL; }
                                        public ResolutionExplanation explanation() {
                                            return ResolutionExplanation.EXPLICIT_VALUE;
                                        }
                                    }
                                    public ResolvedValue<String> keyboardLayoutId() { return null; }
                                    public ResolvedValue<String> voiceRouteId() { return null; }
                                    public ResolvedValue<ProcessingMode> processingMode() { return null; }
                                    public ResolvedValue<Boolean> sendContext() { return null; }
                                    public ResolvedValue<Boolean> historyEnabled() { return null; }
                                    public ResolvedValue<String> actionSetId() { return null; }
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.EffectiveProfileResolver",
                                """
                                package com.opentypeless.android.config;
                                public final class EffectiveProfileResolver {
                                    public static EffectiveProfile resolve(Object request) {
                                        return new EffectiveProfile();
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.config.RuleExplanationModel",
                                """
                                package com.opentypeless.android.config;
                                public class RuleExplanationModel implements java.io.Serializable {
                                    public java.util.Map raw;
                                    public RuleExplanationModel(java.util.Map raw) { this.raw = raw; }
                                    public static RuleExplanationModel from(EffectiveProfile profile) {
                                        EffectiveProfileResolver.resolve(profile);
                                        return new RuleExplanationModel(java.util.Map.of());
                                    }
                                    public java.util.Map items() { return raw; }
                                    public static java.util.List precedence() {
                                        return java.util.List.of();
                                    }
                                    public enum Feature { MAGIC }
                                    public interface DisplayValue {
                                        enum Disabled implements DisplayValue { INSTANCE }
                                        record Identifier(String value) implements DisplayValue {}
                                        record Processing(Object value) implements DisplayValue {}
                                        record BooleanValue(String value) implements DisplayValue {}
                                    }
                                    public record Item(
                                        Feature feature,
                                        Object value,
                                        String source,
                                        String explanation) {}
                                    public interface ExplicitValueFactory {
                                        Object create(Object value);
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.RuleExplanationActivity",
                                """
                                package com.opentypeless.android;
                                public final class RuleExplanationActivity {
                                    public com.opentypeless.android.config.EffectiveProfile.RuleSource
                                        source;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG010_MODEL_SHAPE");
        result.assertFailureContains("CFG010_FEATURE_SHAPE");
        result.assertFailureContains("CFG010_DISPLAY_VALUE_SHAPE");
        result.assertFailureContains("CFG010_ITEM_SHAPE");
        result.assertFailureContains("CFG010_FACTORY_SHAPE");
        result.assertFailureContains("CFG010_MODEL_AUTHORITY");
        result.assertFailureContains("CFG010_PRIORITY_RECOMPUTATION");
        result.assertFailureContains("CFG010_RESOLVER_VOCABULARY_SCOPE");
    }

    @Test
    public void cfg010ProductionExplanationBinariesAreRequiredInEveryVariant() throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "debug",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("CFG010_BINARY_MISSING");
        result.assertFailureContains("RuleExplanationModel");
    }

    @Test
    public void cfg010PrivacyPolicyMayReadResolvedValuesButCannotReadResolverProvenance()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.config.EffectiveProfile",
                                """
                                package com.opentypeless.android.config;
                                public final class EffectiveProfile {
                                    public enum RuleSource { HARD_SAFETY, GLOBAL }
                                    public static final class ResolvedValue<T> {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.security.PrivacyPolicyEngine",
                                """
                                package com.opentypeless.android.security;
                                import com.opentypeless.android.config.EffectiveProfile;
                                public final class PrivacyPolicyEngine {
                                    public EffectiveProfile.ResolvedValue<?> allowedTerminalValue;
                                    public EffectiveProfile.RuleSource forbiddenProvenance;
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains(
                "binary=com/opentypeless/android/security/PrivacyPolicyEngine "
                        + "rule=CFG010_RESOLVER_VOCABULARY_SCOPE");
        result.assertFailureContains("EffectiveProfile$RuleSource");
    }

    @Test
    public void cfg011RejectsOpenTransactionsWrongCallersAndUnredactedRecoveryState()
            throws Exception {
        Path project = compileProject(
                Map.ofEntries(
                        source(
                                "com.opentypeless.android.settings.AppSettings",
                                """
                                package com.opentypeless.android.settings;
                                public final class AppSettings {}
                                """),
                        source(
                                "com.opentypeless.android.security.SecretStore",
                                """
                                package com.opentypeless.android.security;
                                public final class SecretStore {
                                    public static final class LegacyRefs {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.settings.SettingsSaveTransaction",
                                """
                                package com.opentypeless.android.settings;
                                public final class SettingsSaveTransaction {
                                    public interface Recovery {
                                        void restoreFromJournal();
                                        void verifyRestored();
                                        void clearJournal();
                                        String leak();
                                    }
                                    public interface Steps extends Recovery {
                                        void createJournal();
                                        void writeSecrets();
                                        void writeSettings();
                                        void verifyCommitted();
                                    }
                                    public static void execute(Steps steps) {
                                        steps.writeSettings();
                                    }
                                    public static void recover(boolean pending, Recovery steps) {
                                        if (pending) steps.clearJournal();
                                    }
                                    private static void rollback(
                                        Steps steps, RuntimeException failure) {}
                                }
                                """),
                        source(
                                "com.opentypeless.android.settings.SettingsRepository",
                                """
                                package com.opentypeless.android.settings;
                                public final class SettingsRepository {
                                    public static final class RecoveryState {
                                        public AppSettings settings;
                                        public long revision;
                                        public java.util.Map secrets;
                                        public com.opentypeless.android.security.SecretStore.LegacyRefs refs;
                                        public String toString() { return "secret=" + secrets; }
                                    }
                                    private static final class DirectSteps
                                            implements SettingsSaveTransaction.Steps {
                                        public void createJournal() {}
                                        public void writeSecrets() {}
                                        public void writeSettings() {}
                                        public void verifyCommitted() {}
                                        public void restoreFromJournal() {}
                                        public void verifyRestored() {}
                                        public void clearJournal() {}
                                        public String leak() { return "secret"; }
                                    }
                                    private static final class SaveSteps
                                            implements SettingsSaveTransaction.Recovery {
                                        public void restoreFromJournal() {}
                                        public void verifyRestored() {}
                                        public void clearJournal() {}
                                        public String leak() { return "secret"; }
                                    }
                                    private static final class RecoverySteps
                                            implements SettingsSaveTransaction.Steps {
                                        public void createJournal() {}
                                        public void writeSecrets() {}
                                        public void writeSettings() {}
                                        public void verifyCommitted() {}
                                        public void restoreFromJournal() {}
                                        public void verifyRestored() {}
                                        public void clearJournal() {}
                                        public String leak() { return "secret"; }
                                    }
                                    public void save(AppSettings settings) {
                                        helper(new DirectSteps());
                                    }
                                    private void helper(SettingsSaveTransaction.Steps steps) {
                                        SettingsSaveTransaction.execute(steps);
                                    }
                                    public void recoverIfNeeded() {
                                        SettingsSaveTransaction.recover(true, new RecoverySteps());
                                    }
                                }
                                """),
                        source(
                                "com.opentypeless.android.provider.TransactionBypass",
                                """
                                package com.opentypeless.android.provider;
                                public final class TransactionBypass {
                                    public void run(
                                        com.opentypeless.android.settings.SettingsSaveTransaction.Steps
                                            steps) {
                                        com.opentypeless.android.settings.SettingsSaveTransaction
                                            .execute(steps);
                                    }
                                }
                                """)));

        GateResult result = audit(project);
        result.assertFailureContains("CFG011_TRANSACTION_SHAPE");
        result.assertFailureContains("CFG011_RECOVERY_SHAPE");
        result.assertFailureContains("CFG011_STEPS_SHAPE");
        result.assertFailureContains("CFG011_JOURNAL_STATE_SHAPE");
        result.assertFailureContains("CFG011_SAVE_STEPS_BINDING");
        result.assertFailureContains("CFG011_RECOVERY_STEPS_BINDING");
        result.assertFailureContains("CFG011_TRANSACTION_AUTHORITY");
        result.assertFailureContains("CFG011_TRANSACTION_CALLER");
        result.assertFailureContains("CFG011_REPOSITORY_EXACT_EDGE");
    }

    @Test
    public void cfg011ProductionTransactionBinariesAreRequiredInEveryVariant() throws Exception {
        Path project = compileProject(
                Map.of(
                        "com.opentypeless.android.placeholder.Empty",
                        """
                        package com.opentypeless.android.placeholder;
                        public final class Empty {}
                        """));
        GateResult result = runGate(
                "release",
                writeManifest(List.of(project)),
                writeManifest(List.of(project, androidAndThirdPartyClasses)));
        result.assertFailureContains("CFG011_BINARY_MISSING");
        result.assertFailureContains("SettingsRepository");
        result.assertFailureContains("SettingsSaveTransaction");
    }

    private static Path compileProject(Map<String, String> sources) throws Exception {
        Path output = Files.createTempDirectory(workspace, "project-classes-");
        compile(output, sources, List.of(androidAndThirdPartyClasses));
        return output;
    }

    private static void compile(Path output, Map<String, String> sources, List<Path> classpath)
            throws IOException {
        Files.createDirectories(output);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("--release", "17", "-d", output.toString()));
            if (!classpath.isEmpty()) {
                options.add("-classpath");
                options.add(String.join(System.getProperty("path.separator"),
                        classpath.stream().map(Path::toString).toList()));
            }
            List<JavaFileObject> units = sources.entrySet().stream()
                    .map(entry -> new StringSource(entry.getKey(), entry.getValue()))
                    .map(JavaFileObject.class::cast)
                    .toList();
            boolean succeeded = compiler.getTask(null, fileManager, diagnostics, options, null, units)
                    .call();
            assertTrue("Fixture compilation failed: " + diagnostics.getDiagnostics(), succeeded);
        }
    }

    private static Map.Entry<String, String> source(String className, String source) {
        return Map.entry(className, source);
    }

    private static GateResult audit(Path projectArtifact) throws Exception {
        return runGate(
                writeManifest(List.of(projectArtifact)),
                writeManifest(List.of(projectArtifact, androidAndThirdPartyClasses)));
    }

    private static GateResult runGate(Path projectManifest, Path allManifest) throws Exception {
        return runGate("fixture", projectManifest, allManifest);
    }

    private static GateResult runGate(
            String variant, Path projectManifest, Path allManifest) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = CompiledArchitectureGate.run(
                    new String[] {
                        "--variant",
                        variant,
                        "--project-manifest",
                        projectManifest.toString(),
                        "--all-manifest",
                        allManifest.toString()
                    },
                    out,
                    err);
        }
        return new GateResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8)
                        + stderr.toString(StandardCharsets.UTF_8));
    }

    private static Path writeManifest(List<Path> artifacts) throws IOException {
        Path manifest = Files.createTempFile(workspace, "architecture-", ".paths");
        List<String> sorted = artifacts.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .distinct()
                .sorted()
                .toList();
        try (OutputStream output = Files.newOutputStream(
                manifest, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (String path : sorted) {
                output.write(path.getBytes(StandardCharsets.UTF_8));
                output.write(0);
            }
        }
        return manifest;
    }

    private static Path jar(Path classes, String fileName) throws IOException {
        Path target = workspace.resolve(fileName);
        try (OutputStream output = Files.newOutputStream(target);
                JarOutputStream jar = new JarOutputStream(output);
                Stream<Path> paths = Files.walk(classes)) {
            for (Path classFile : paths.filter(Files::isRegularFile).sorted().toList()) {
                String entryName = classes.relativize(classFile).toString().replace('\\', '/');
                jar.putNextEntry(new JarEntry(entryName));
                Files.copy(classFile, jar);
                jar.closeEntry();
            }
        }
        return target;
    }

    private static void writeUnknownBootstrapClass(Path root, String internalName)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null);
        MethodVisitor constructor =
                writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_STATIC, "value", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitInvokeDynamicInsn(
                "value",
                "()Ljava/lang/Object;",
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "com/opentypeless/android/provider/UntrustedBootstrapHost",
                        "bootstrap",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        false));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();

        Path classFile = root.resolve(internalName + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, writer.toByteArray());
    }

    private static void writeConstantDynamicClass(Path root, String internalName)
            throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName,
                null,
                "java/lang/Object",
                null);
        MethodVisitor constructor =
                writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_STATIC, "value", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        method.visitLdcInsn(
                new org.objectweb.asm.ConstantDynamic(
                        "value",
                        "Ljava/lang/Object;",
                        new Handle(
                                Opcodes.H_INVOKESTATIC,
                                "java/lang/runtime/ObjectMethods",
                                "bootstrap",
                                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                        + "Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;"
                                        + "Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)"
                                        + "Ljava/lang/Object;",
                                false)));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();

        Path classFile = root.resolve(internalName + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, writer.toByteArray());
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup of an isolated test-only temporary directory.
        }
    }

    private record GateResult(int exitCode, String output) {
        void assertSuccess() {
            assertEquals("Gate should pass, output:\n" + output, 0, exitCode);
        }

        void assertFailureContains(String expected) {
            assertNotEquals("Gate should fail, output:\n" + output, 0, exitCode);
            assertTrue(
                    "Expected gate output to contain '" + expected + "', actual:\n" + output,
                    output.toUpperCase().contains(expected.toUpperCase()));
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String source;

        StringSource(String className, String source) {
            super(
                    URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
