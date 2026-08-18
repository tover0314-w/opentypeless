from pathlib import Path
import tempfile
import textwrap
import unittest

from architecture_contracts import (
    JavaUnicodeEscapeError,
    _translate_java_unicode_escapes,
    inspect_android_project,
    inspect_source_tree,
)


class ArchitectureContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.production_sources = (
            Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "java"
        )

    def test_current_production_source_satisfies_architecture_boundaries(self):
        violations = inspect_source_tree(self.production_sources)
        self.assertEqual((), violations, "\n" + "\n".join(map(str, violations)))

    def test_java_unicode_translation_matches_jls_eligibility_examples(self):
        self.assertEqual("™=™", _translate_java_unicode_escapes(r"\u2122=\u2122"))
        self.assertEqual(r"\\u2122", _translate_java_unicode_escapes(r"\\u2122"))
        self.assertEqual("\\\\™", _translate_java_unicode_escapes(r"\\\u2122"))
        self.assertEqual(r"\u005a", _translate_java_unicode_escapes(r"\u005cu005a"))
        self.assertEqual("\\Z", _translate_java_unicode_escapes(r"\u005c\u005a"))
        self.assertEqual("A", _translate_java_unicode_escapes(r"\uuuu0041"))
        with self.assertRaises(JavaUnicodeEscapeError):
            _translate_java_unicode_escapes(r"\u12xy")

    def test_java_unicode_translation_tracks_consecutive_escaped_backslashes(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/EscapedBackslashes.java",
            r"""
            package com.opentypeless.android.recognition;
            // bypass \u005c\u005c\\\u000a
            import android.view.inputmethod.InputConnection;
            final class EscapedBackslashes { InputConnection connection; }
            """,
        )
        self.assertEqual(
            {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_rejects_input_connection_in_provider(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.view.inputmethod.InputConnection;
            final class BadProvider { InputConnection connection; }
            """,
        )
        self.assertEqual(
            {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_rejects_wildcard_input_connection_import_in_provider(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/WildcardProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.view.inputmethod.*;
            final class WildcardProvider { InputConnection connection; }
            """,
        )
        self.assertEqual(
            {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_rejects_minified_single_line_package_and_import_bypass(self):
        fixtures = (
            (
                "com/opentypeless/android/recognition/Bad.java",
                "package com.opentypeless.android.recognition; import android.view.inputmethod.*; final class Bad { InputConnection c; }",
                {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            ),
            (
                "com/opentypeless/android/actions/Bad.java",
                "package com.opentypeless.android.actions; import com.opentypeless.android.editor.host.InputConnectionRegistry; final class Bad { InputConnectionRegistry r; }",
                {"EDITOR_HOST_CAPABILITY_BOUNDARY", "UI_PROVIDER_EDITOR_CAPABILITY"},
            ),
            (
                "com/opentypeless/android/editor/core/Bad.java",
                "package com.opentypeless.android.editor.core; import android.os.SystemClock; import com.opentypeless.android.editor.host.InputConnectionRegistry; final class Bad {}",
                {
                    "EDITOR_HOST_CAPABILITY_BOUNDARY",
                    "PURE_DOMAIN_ANDROID_DEPENDENCY",
                    "PURE_DOMAIN_HOST_DEPENDENCY",
                },
            ),
        )
        for relative_path, source, expected_rules in fixtures:
            with self.subTest(relative_path=relative_path):
                violations = self.inspect_fixture(relative_path, source)
                self.assertEqual(expected_rules, {item.rule for item in violations})

    def test_rejects_comments_and_whitespace_inside_qualified_names(self):
        fixtures = (
            (
                "com/opentypeless/android/recognition/Commented.java",
                """
                package com . opentypeless/**/. android . recognition;
                import android . view . inputmethod . /**/ InputConnection;
                final class Commented { InputConnection connection; }
                """,
            ),
            (
                "com/opentypeless/android/recognition/CommentedFqcn.java",
                """
                package com.opentypeless.android.recognition;
                final class CommentedFqcn {
                    android . view . inputmethod . /**/ InputConnection connection;
                }
                """,
            ),
            (
                "com/opentypeless/android/recognition/Commented.kt",
                """
                package com . opentypeless . android . recognition
                import android . view . inputmethod . InputConnection
                internal class Commented(val connection: InputConnection)
                """,
            ),
        )
        for relative_path, source in fixtures:
            with self.subTest(relative_path=relative_path):
                violations = self.inspect_fixture(relative_path, source)
                self.assertEqual(
                    {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
                    {item.rule for item in violations},
                )

    def test_java_unicode_translation_cannot_hide_capabilities_or_writes(self):
        provider_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/Encoded.java",
            r"""
            package com.opentypeless.android.recogni\u0074ion;
            \u0069mport android.view.inputmethod.InputConnec\u0074ion;
            final class Encoded { InputConnection connection; }
            """,
        )
        self.assertEqual(
            {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in provider_violations},
        )

        writer_violations = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            r"""
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            final class OpenTypelessImeService {
                boolean write(InputConnection connection) {
                    return connection.commit\u0054ext("x", 1);
                }
            }
            """,
        )
        self.assertIn("EDITOR_WRITE_RATCHET", {item.rule for item in writer_violations})

    def test_java_identifier_ignorable_characters_cannot_hide_capabilities_or_writes(self):
        for encoded_character in (r"\u0000", r"\u200b"):
            with self.subTest(encoded_character=encoded_character):
                provider_violations = self.inspect_fixture(
                    "com/opentypeless/android/recognition/Ignorable.java",
                    rf"""
                    package com.opentypeless.android.recognition;
                    import android.view.inputmethod.InputConnec{encoded_character}tion;
                    final class Ignorable {{ InputConnection connection; }}
                    """,
                )
                self.assertEqual(
                    {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
                    {item.rule for item in provider_violations},
                )

        writer_violations = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            r"""
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            final class OpenTypelessImeService {
                boolean write(InputConnection connection) {
                    return connection.commit\u0000Text("x", 1);
                }
            }
            """,
        )
        self.assertIn("EDITOR_WRITE_RATCHET", {item.rule for item in writer_violations})

        reflective_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/IgnorableReflection.java",
            """
            package com.opentypeless.android.recognition;
            final class IgnorableReflection {
                Object type(String value) throws Exception {
                    return Class.for\u200bName(value);
                }
            }
            """,
        )
        self.assertIn(
            "REFLECTIVE_TYPE_LOADING", {item.rule for item in reflective_violations}
        )

    def test_java_unicode_created_line_terminator_is_processed_before_comments(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/EncodedLine.java",
            r"""
            package com.opentypeless.android.recognition;
            // the following escape ends this source comment\u000a
            import android.view.inputmethod.InputConnection;
            final class EncodedLine { InputConnection connection; }
            """,
        )
        self.assertEqual(
            {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_valid_java_unicode_literals_and_ineligible_backslashes_remain_allowed(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/misc/UnicodeLiterals.java",
            r'''
            package com.opentypeless.android.misc;
            final class UnicodeLiterals {
                char delimiter = '\u001f';
                String escaped = "\\u0069";
            }
            ''',
        )
        self.assertEqual((), violations)

    def test_malformed_compiler_eligible_java_unicode_escape_fails_closed(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/misc/Malformed.java",
            r"""
            package com.opentypeless.android.misc;
            final class Malformed { String value = "\u00zz"; }
            """,
        )
        self.assertEqual(
            ["JAVA_UNICODE_ESCAPE_SYNTAX"], [item.rule for item in violations]
        )

    def test_exact_owner_path_cannot_declare_an_untrusted_package(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/InputConnectionRegistry.java",
            """
            package com.opentypeless.android.misc;
            import android.view.inputmethod.InputConnection;
            final class Evil { InputConnection connection; }
            """,
        )
        self.assertEqual(
            ["SOURCE_PACKAGE_MISMATCH"], [item.rule for item in violations]
        )

    def test_rejects_input_connection_implementation_in_provider(self):
        fixtures = (
            (
                "import android.view.inputmethod.BaseInputConnection;",
                "BaseInputConnection connection;",
            ),
            (
                "import android.inputmethodservice.*;",
                "InputMethodService service;",
            ),
        )
        for import_statement, field in fixtures:
            with self.subTest(import_statement=import_statement):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/recognition/BaseConnectionProvider.java",
                    f"""
                    package com.opentypeless.android.recognition;
                    {import_statement}
                    final class BaseConnectionProvider {{ {field} }}
                    """,
                )
                self.assertEqual(
                    {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
                    {item.rule for item in violations},
                )

    def test_rejects_input_connection_type_use_annotation_bypass(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/AnnotatedConnection.java",
            """
            package com.opentypeless.android.recognition;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE_USE) @interface CapabilityMarker {}
            final class AnnotatedConnection {
                android.view.inputmethod.@CapabilityMarker InputConnection connection;
            }
            """,
        )
        self.assertEqual(
            {
                "FORBIDDEN_CAPABILITY_TYPE_ANNOTATION",
                "INPUT_CONNECTION_OWNER",
                "UI_PROVIDER_EDITOR_CAPABILITY",
            },
            {item.rule for item in violations},
        )

    def test_kotlin_escaped_identifiers_fail_closed(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/Escaped.kt",
            """
            package com.opentypeless.android.recognition
            import android.view.inputmethod.`InputConnection`
            internal class Escaped(private val connection: `InputConnection`) {
                fun write() = connection.`commitText`("x", 1)
            }
            """,
        )
        self.assertEqual(
            ["KOTLIN_ESCAPED_IDENTIFIER"], [item.rule for item in violations]
        )

    def test_java_text_block_escaped_quotes_cannot_hide_writes(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            r'''
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            final class OpenTypelessImeService {
                String bait = """
                    \"""
                    """;
                boolean write(InputConnection connection) {
                    return connection.commitText("x", 1);
                }
            }
            ''',
        )
        self.assertIn("EDITOR_WRITE_RATCHET", {item.rule for item in violations})

    def test_java_explicit_type_arguments_cannot_hide_writes(self):
        expressions = (
            'connection.<java.lang.Object>commitText("x", 1)',
            'connection.<java.util.Map<String,String>>commitText("x", 1)',
            "connection::<java.util.Map<String,String>>commitText",
        )
        for expression in expressions:
            with self.subTest(expression=expression):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/ime/OpenTypelessImeService.java",
                    f"""
                    package com.opentypeless.android.ime;
                    import android.view.inputmethod.InputConnection;
                    final class OpenTypelessImeService {{
                        Object write(InputConnection connection) {{
                            return {expression};
                        }}
                    }}
                    """,
                )
                self.assertIn(
                    "EDITOR_WRITE_RATCHET", {item.rule for item in violations}
                )

    def test_type_use_annotations_cannot_split_any_forbidden_capability(self):
        prefixes = (
            ("android.inputmethodservice", "InputMethodService"),
            ("com.opentypeless.android.ime", "OpenTypelessImeService"),
        )
        for prefix, type_name in prefixes:
            with self.subTest(prefix=prefix):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/recognition/AnnotatedCapability.java",
                    f"""
                    package com.opentypeless.android.recognition;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Target;
                    @Target(ElementType.TYPE_USE) @interface A {{}}
                    final class AnnotatedCapability {{ {prefix}.@A {type_name} value; }}
                    """,
                )
                self.assertIn(
                    "FORBIDDEN_CAPABILITY_TYPE_ANNOTATION",
                    {item.rule for item in violations},
                )

    def test_nested_type_use_annotation_arguments_fail_closed(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/NestedAnnotation.java",
            """
            package com.opentypeless.android.recognition;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @interface B { int x(); }
            @Target(ElementType.TYPE_USE) @interface A { B value(); }
            final class NestedAnnotation {
                android.view.inputmethod.@A(value=@B(x=1)) InputConnection value;
            }
            """,
        )
        self.assertIn(
            "FORBIDDEN_CAPABILITY_TYPE_ANNOTATION", {item.rule for item in violations}
        )

    def test_bare_and_typed_classloader_calls_fail_closed(self):
        statements = (
            'return loadClass(prefix + "InputConnection");',
            'return findSystemClass(prefix + "InputConnection");',
            'return ClassLoader.getSystemClassLoader().<Object>loadClass(prefix + "InputConnection");',
        )
        for statement in statements:
            with self.subTest(statement=statement):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/recognition/Loader.java",
                    f"""
                    package com.opentypeless.android.recognition;
                    final class Loader extends ClassLoader {{
                        Object type(String prefix) throws Exception {{ {statement} }}
                    }}
                    """,
                )
                self.assertIn(
                    "REFLECTIVE_TYPE_LOADING", {item.rule for item in violations}
                )

    def test_reflection_enumeration_and_fqcn_fail_closed(self):
        fixtures = (
            "return Class.class.getMethods();",
            "return Class.class.getDeclaredMethods();",
            "return java.lang.reflect.Proxy.newProxyInstance(null, null, null);",
        )
        for statement in fixtures:
            with self.subTest(statement=statement):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/misc/ReflectionBypass.java",
                    f"""
                    package com.opentypeless.android.misc;
                    final class ReflectionBypass {{
                        Object value() throws Exception {{ {statement} }}
                    }}
                    """,
                )
                self.assertTrue(
                    {"REFLECTION_CAPABILITY", "REFLECTIVE_METHOD_ACCESS"}
                    & {item.rule for item in violations}
                )

    def test_same_package_ime_accessor_is_rejected(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/ime/SamePackageLeakActivity.java",
            """
            package com.opentypeless.android.ime;
            final class SamePackageLeakActivity {
                Object leak(OpenTypelessImeService service) {
                    return service.getCurrentInputConnection();
                }
            }
            """,
        )
        self.assertIn(
            "INPUT_CONNECTION_ACCESSOR", {item.rule for item in violations}
        )

    def test_reflection_packages_are_default_deny(self):
        for imported in ("java.lang.reflect.*", "java.lang.invoke.MethodHandles"):
            with self.subTest(imported=imported):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/misc/Reflection.java",
                    f"""
                    package com.opentypeless.android.misc;
                    import {imported};
                    final class Reflection {{}}
                    """,
                )
                self.assertEqual(
                    ["REFLECTION_CAPABILITY"], [item.rule for item in violations]
                )

    def test_rejects_access_to_public_ime_input_connection_capability(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/ImeLeak.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.ime.OpenTypelessImeService;
            final class ImeLeak {
                Object capture(OpenTypelessImeService service) {
                    return service.getCurrentInputConnection();
                }
            }
            """,
        )
        self.assertEqual(
            {"INPUT_CONNECTION_ACCESSOR", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_reflective_type_loading_is_default_deny(self):
        fixtures = (
            r'''return Class.forName("android.view.inputmethod." + "InputConnection");''',
            r'''return forName(prefix + "InputConnection");''',
            r'''Loader loader = Class::forName; return loader.load(prefix + "InputConnection");''',
            r'''return MethodHandles.lookup().findClass(prefix + "InputConnection");''',
        )
        for statement in fixtures:
            with self.subTest(statement=statement):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/recognition/ReflectiveProvider.java",
                    f"""
                    package com.opentypeless.android.recognition;
                    final class ReflectiveProvider {{
                        Object type(String prefix) throws Exception {{ {statement} }}
                    }}
                    """,
                )
                self.assertIn(
                    "REFLECTIVE_TYPE_LOADING", {item.rule for item in violations}
                )

    def test_rejects_input_connection_in_activity(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/BadActivity.java",
            """
            package com.opentypeless.android;
            final class BadActivity {
                android.view.inputmethod.InputConnection connection;
            }
            """,
        )
        self.assertEqual(
            {
                "INPUT_CONNECTION_OWNER",
                "UI_PROVIDER_EDITOR_CAPABILITY",
            },
            {item.rule for item in violations},
        )

    def test_rejects_kotlin_alias_import_in_provider(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/offline/BadProvider.kt",
            """
            package com.opentypeless.android.offline
            import android.view.inputmethod.InputConnection as EditorConnection
            internal class BadProvider(val connection: EditorConnection)
            """,
        )
        self.assertEqual(
            {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_rejects_provider_import_of_legacy_write_capability(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadProvider.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.speech.delivery.ProjectionConnection;
            final class BadProvider { ProjectionConnection connection; }
            """,
        )
        self.assertEqual(
            ["UI_PROVIDER_EDITOR_CAPABILITY"], [item.rule for item in violations]
        )

    def test_rejects_provider_import_of_future_connection_registry(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/net/BadProvider.java",
            """
            package com.opentypeless.android.net;
            import com.opentypeless.android.editor.host.InputConnectionRegistry;
            final class BadProvider { InputConnectionRegistry registry; }
            """,
        )
        self.assertEqual(
            {"EDITOR_HOST_CAPABILITY_BOUNDARY", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_rejects_editor_host_capability_in_action_llm_rime_and_ui_components(self):
        fixtures = (
            ("com/opentypeless/android/actions/BadAction.java", "com.opentypeless.android.actions", "BadAction"),
            ("com/opentypeless/android/llm/BadLlm.java", "com.opentypeless.android.llm", "BadLlm"),
            ("com/opentypeless/android/rime/BadRime.java", "com.opentypeless.android.rime", "BadRime"),
            ("com/opentypeless/android/settings/BadFragment.java", "com.opentypeless.android.settings", "BadFragment"),
            ("com/opentypeless/android/settings/BadScreen.java", "com.opentypeless.android.settings", "BadScreen"),
        )
        for relative_path, package_name, class_name in fixtures:
            with self.subTest(relative_path=relative_path):
                violations = self.inspect_fixture(
                    relative_path,
                    f"""
                    package {package_name};
                    import com.opentypeless.android.editor.host.InputConnectionRegistry;
                    final class {class_name} {{ InputConnectionRegistry registry; }}
                    """,
                )
                self.assertEqual(
                    {"EDITOR_HOST_CAPABILITY_BOUNDARY", "UI_PROVIDER_EDITOR_CAPABILITY"},
                    {item.rule for item in violations},
                )

    def test_rejects_unregistered_editor_host_input_connection_owner(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/ShortcutConnection.java",
            """
            package com.opentypeless.android.editor.host;
            import android.view.inputmethod.InputConnection;
            final class ShortcutConnection { InputConnection connection; }
            """,
        )
        self.assertEqual(
            ["INPUT_CONNECTION_OWNER"], [item.rule for item in violations]
        )

    def test_editor_session_manager_is_exact_host_owner_but_cannot_write(self):
        allowed = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            import android.view.inputmethod.InputConnection;
            public final class EditorSessionManager { InputConnection observed; }
            """,
        )
        self.assertEqual((), allowed)

        for method in (
            "commitText",
            "setComposingText",
            "finishComposingText",
            "deleteSurroundingText",
        ):
            with self.subTest(method=method):
                invocation = {
                    "commitText": 'connection.commitText("x", 1)',
                    "setComposingText": 'connection.setComposingText("x", 1)',
                    "finishComposingText": "connection.finishComposingText()",
                    "deleteSurroundingText": "connection.deleteSurroundingText(1, 0)",
                }[method]
                violations = self.inspect_fixture(
                    "com/opentypeless/android/editor/host/EditorSessionManager.java",
                    f"""
                    package com.opentypeless.android.editor.host;
                    import android.view.inputmethod.InputConnection;
                    public final class EditorSessionManager {{
                        void write(InputConnection connection) {{ {invocation}; }}
                    }}
                    """,
                )
                self.assertIn("EDITOR_WRITE_OWNER", {item.rule for item in violations})

    def test_editor_transaction_manager_is_the_exact_narrow_writer(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            import android.view.inputmethod.InputConnection;
            final class EditorTransactionManager {
                boolean beginBatch(InputConnection connection) {
                    return connection.beginBatchEdit();
                }
                void finishBatch(InputConnection connection) {
                    connection.endBatchEdit();
                }
                boolean invokeMutator(
                        InputConnection connection,
                        com.opentypeless.android.editor.EditorOperation operation) {
                    return connection.commitText("x", 1)
                            && connection.deleteSurroundingTextInCodePoints(1, 0)
                            && connection.setComposingText("composition", 1)
                            && connection.finishComposingText()
                            && connection.performEditorAction(1);
                }
            }
            """,
        )
        self.assertEqual((), violations)

    def test_editor_transaction_manager_rejects_every_out_of_scope_mutator(self):
        invocations = (
            'connection.sendKeyEvent(null)',
            'connection.setComposingRegion(0, 1)',
            'connection.deleteSurroundingText(1, 0)',
            'connection.setSelection(0, 0)',
            'connection.performPrivateCommand("x", null)',
            'connection.requestCursorUpdates(1)',
        )
        for invocation in invocations:
            with self.subTest(invocation=invocation):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/editor/host/EditorTransactionManager.java",
                    f"""
                    package com.opentypeless.android.editor.host;
                    import android.view.inputmethod.InputConnection;
                    final class EditorTransactionManager {{
                        void apply(InputConnection connection) {{ {invocation}; }}
                    }}
                    """,
                )
                self.assertIn(
                    "EDITOR_TRANSACTION_WRITE_SURFACE",
                    {item.rule for item in violations},
                )

    def test_editor_transaction_writer_permission_is_not_inherited_by_name_or_package(self):
        fixtures = (
            (
                "com/opentypeless/android/editor/host/EditorTransactionManager$Evil.java",
                "EditorTransactionManager$Evil",
            ),
            (
                "com/opentypeless/android/editor/host/ShortcutWriter.java",
                "ShortcutWriter",
            ),
        )
        for relative_path, class_name in fixtures:
            with self.subTest(relative_path=relative_path):
                violations = self.inspect_fixture(
                    relative_path,
                    f"""
                    package com.opentypeless.android.editor.host;
                    import android.view.inputmethod.InputConnection;
                    final class {class_name} {{
                        void apply(InputConnection connection) {{
                            connection.setComposingText("x", 1);
                            connection.finishComposingText();
                        }}
                    }}
                    """,
                )
                self.assertIn("EDITOR_WRITE_OWNER", {item.rule for item in violations})
                self.assertIn("INPUT_CONNECTION_OWNER", {item.rule for item in violations})

        provider = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadCompositionProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.view.inputmethod.InputConnection;
            final class BadCompositionProvider {
                void apply(InputConnection connection) {
                    connection.setComposingText("x", 1);
                    connection.finishComposingText();
                }
            }
            """,
        )
        self.assertIn("EDITOR_WRITE_OWNER", {item.rule for item in provider})
        self.assertIn("INPUT_CONNECTION_OWNER", {item.rule for item in provider})
        self.assertIn("UI_PROVIDER_EDITOR_CAPABILITY", {item.rule for item in provider})

    def test_only_editor_session_manager_may_reference_transaction_capability(self):
        allowed = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            public final class EditorSessionManager {
                void apply(EditorTransactionManager transaction) {}
            }
            """,
        )
        self.assertEqual((), allowed)

        provider = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadProvider.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.editor.host.EditorTransactionManager;
            final class BadProvider { EditorTransactionManager transaction; }
            """,
        )
        self.assertEqual(
            {
                "EDITOR_HOST_CAPABILITY_BOUNDARY",
                "EDITOR_TRANSACTION_CAPABILITY_BOUNDARY",
                "UI_PROVIDER_EDITOR_CAPABILITY",
            },
            {item.rule for item in provider},
        )

        other_host = self.inspect_fixture(
            "com/opentypeless/android/editor/host/TransactionHelper.java",
            """
            package com.opentypeless.android.editor.host;
            final class TransactionHelper { EditorTransactionManager transaction; }
            """,
        )
        self.assertEqual(
            ["EDITOR_TRANSACTION_CAPABILITY_BOUNDARY"],
            [item.rule for item in other_host],
        )

    def test_editor_transaction_manager_must_be_package_private_final(self):
        for declaration in (
            "public final class EditorTransactionManager",
            "class EditorTransactionManager",
            "abstract class EditorTransactionManager",
        ):
            with self.subTest(declaration=declaration):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/editor/host/EditorTransactionManager.java",
                    f"""
                    package com.opentypeless.android.editor.host;
                    {declaration} {{}}
                    """,
                )
                self.assertEqual(
                    ["EDITOR_TRANSACTION_DECLARATION"],
                    [item.rule for item in violations],
                )

    def test_editor_transaction_manager_cannot_obtain_or_indirectly_send_through_ime(self):
        for invocation in (
            "service.getCurrentInputConnection()",
            "service.getCurrentInputBinding()",
            "service.sendDefaultEditorAction(false)",
            "service.sendDownUpKeyEvents(66)",
            "service.sendKeyChar('x')",
        ):
            with self.subTest(invocation=invocation):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/editor/host/EditorTransactionManager.java",
                    f"""
                    package com.opentypeless.android.editor.host;
                    final class EditorTransactionManager {{
                        Object apply(Service service) {{ return {invocation}; }}
                    }}
                    """,
                )
                self.assertIn(
                    "EDITOR_TRANSACTION_INDIRECT_IME_ACCESS",
                    {item.rule for item in violations},
                )

    def test_editor_evidence_reader_is_exact_read_only_owner(self):
        allowed = self.inspect_fixture(
            "com/opentypeless/android/ime/EditorEvidenceReader.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            final class EditorEvidenceReader {
                CharSequence read(InputConnection connection) {
                    return connection.getSelectedText(0);
                }
            }
            """,
        )
        self.assertEqual((), allowed)

        denied = self.inspect_fixture(
            "com/opentypeless/android/ime/EditorEvidenceReader.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            final class EditorEvidenceReader {
                void write(InputConnection connection) { connection.commitText("x", 1); }
            }
            """,
        )
        self.assertIn("EDITOR_WRITE_OWNER", {item.rule for item in denied})

    def test_rejects_new_editor_write_call(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/ime/ShortcutWriter.java",
            """
            package com.opentypeless.android.ime;
            final class ShortcutWriter {
                void write(Connection value) { value.commitText("x", 1); }
            }
            """,
        )
        self.assertEqual(["EDITOR_WRITE_OWNER"], [item.rule for item in violations])

    def test_rejects_input_method_service_indirect_editor_writes(self):
        for invocation in (
            "this.finishConnectionlessStylusHandwriting(\"text\")",
            "this.finishStylusHandwriting()",
            "this.onExtractedCursorMovement(1, 0)",
            "this.onExtractedSelectionChanged(1, 1)",
            "this.onExtractTextContextMenuItem(16908320)",
            "this.sendDefaultEditorAction(false)",
            "this.sendDownUpKeyEvents(66)",
            "this.sendKeyChar('x')",
        ):
            with self.subTest(invocation=invocation):
                violations = self.inspect_fixture(
                    "com/opentypeless/android/ime/ShortcutIme.java",
                    f"""
                    package com.opentypeless.android.ime;
                    import android.inputmethodservice.InputMethodService;
                    final class ShortcutIme extends InputMethodService {{
                        void write() {{ {invocation}; }}
                    }}
                    """,
                )
                self.assertEqual(
                    {"EDITOR_WRITE_OWNER", "INPUT_CONNECTION_OWNER"},
                    {item.rule for item in violations},
                )

        method_reference = self.inspect_fixture(
            "com/opentypeless/android/ime/ShortcutIme.java",
            """
            package com.opentypeless.android.ime;
            import android.inputmethodservice.InputMethodService;
            final class ShortcutIme extends InputMethodService {
                interface CharSender { void send(char value); }
                CharSender writer() { return this::sendKeyChar; }
            }
            """,
        )
        self.assertEqual(
            {"EDITOR_WRITE_OWNER", "INPUT_CONNECTION_OWNER"},
            {item.rule for item in method_reference},
        )

    def test_rejects_editor_write_method_reference_and_reflective_method_lookup(self):
        method_reference = self.inspect_fixture(
            "com/opentypeless/android/ime/ShortcutReference.java",
            """
            package com.opentypeless.android.ime;
            final class ShortcutReference {
                Object write(Connection connection) { return connection::commitText; }
            }
            """,
        )
        self.assertEqual(["EDITOR_WRITE_OWNER"], [item.rule for item in method_reference])

        reflective = self.inspect_fixture(
            "com/opentypeless/android/recognition/ReflectiveMethod.java",
            """
            package com.opentypeless.android.recognition;
            final class ReflectiveMethod {
                Object write(Class<?> connection) throws Exception {
                    return connection.getMethod("commitText", CharSequence.class, int.class);
                }
            }
            """,
        )
        self.assertEqual(
            ["REFLECTIVE_METHOD_ACCESS"], [item.rule for item in reflective]
        )

    def test_rejects_input_connection_set_selection_but_not_widget_selection(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/ime/SelectionWriter.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            final class SelectionWriter {
                void write(InputConnection value) { value.setSelection(1, 1); }
            }
            """,
        )
        self.assertEqual(
            {"EDITOR_WRITE_OWNER", "INPUT_CONNECTION_OWNER"},
            {item.rule for item in violations},
        )

        widget_violations = self.inspect_fixture(
            "com/opentypeless/android/SettingsActivity.java",
            """
            package com.opentypeless.android;
            final class SettingsActivity {
                void select(Spinner value) { value.setSelection(1); }
            }
            """,
        )
        self.assertEqual((), widget_violations)

    def test_rejects_change_to_legacy_writer_call_inventory(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            final class OpenTypelessImeService {
                void write(InputConnection value) { value.commitText("extra", 1); }
            }
            """,
        )
        self.assertEqual(
            {"EDITOR_WRITE_RATCHET", "STR010_PRODUCTION_CALLER"},
            {item.rule for item in violations},
        )

    def test_comments_and_string_method_names_do_not_create_write_calls(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/ime/Harmless.java",
            r'''
            package com.opentypeless.android.ime;
            // connection.commitText("comment", 1);
            final class Harmless {
                String example = "connection.setComposingText(text, 1)";
            }
            ''',
        )
        self.assertEqual((), violations)

    def test_reflective_input_connection_name_is_rejected(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/ReflectiveProvider.java",
            r'''
            package com.opentypeless.android.recognition;
            final class ReflectiveProvider {
                String type = "android.view.inputmethod.InputConnection";
            }
            ''',
        )
        self.assertEqual(
            {"INPUT_CONNECTION_OWNER", "UI_PROVIDER_EDITOR_CAPABILITY"},
            {item.rule for item in violations},
        )

    def test_rejects_android_dependency_in_pure_domain_package(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/speech/core/AndroidClock.java",
            """
            package com.opentypeless.android.speech.core;
            import android.os.SystemClock;
            final class AndroidClock {}
            """,
        )
        self.assertEqual(
            ["PURE_DOMAIN_ANDROID_DEPENDENCY"], [item.rule for item in violations]
        )

    def test_rejects_android_dependency_in_editor_domain_package(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/AndroidClock.java",
            """
            package com.opentypeless.android.editor;
            import android.os.SystemClock;
            final class AndroidClock {}
            """,
        )
        self.assertEqual(
            ["PURE_DOMAIN_ANDROID_DEPENDENCY"], [item.rule for item in violations]
        )

    def test_rejects_serialization_contracts_in_pure_editor_models(self):
        fixtures = (
            (
                "com/opentypeless/android/editor/SerializableRecord.java",
                """
                package com.opentypeless.android.editor;
                final class SerializableRecord implements java.io.Serializable {}
                """,
            ),
            (
                "com/opentypeless/android/editor/JsonRecord.java",
                """
                package com.opentypeless.android.editor;
                import org.json.JSONObject;
                final class JsonRecord { JSONObject encoded; }
                """,
            ),
        )
        for relative_path, source in fixtures:
            with self.subTest(relative_path=relative_path):
                rules = {item.rule for item in self.inspect_fixture(relative_path, source)}
                self.assertIn("EDITOR_MODEL_SERIALIZATION_DEPENDENCY", rules)

    def test_commit_envelopes_reject_throwables_and_execution_capabilities(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/CommitRecord.java",
            """
            package com.opentypeless.android.editor;
            public final class CommitRecord {
                private final Throwable failure = null;
                private final Runnable retry = null;
            }
            """,
        )
        rules = {item.rule for item in violations}
        self.assertIn("COMMIT_ENVELOPE_THROWABLE", rules)
        self.assertIn("COMMIT_ENVELOPE_EXECUTION_CAPABILITY", rules)

    def test_transaction_audit_model_and_kind_are_exact_content_free_values(self):
        audit_violations = self.inspect_fixture(
            "com/opentypeless/android/editor/EditorTransactionAudit.java",
            """
            package com.opentypeless.android.editor;
            public record EditorTransactionAudit(
                    OperationSource source,
                    EditorOperationKind operationKind,
                    EditorTransactionResult result,
                    String text) {
                private static String retainedBody;
            }
            """,
        )
        audit_rules = {item.rule for item in audit_violations}
        self.assertIn("EDITOR_TRANSACTION_AUDIT_SHAPE", audit_rules)
        self.assertIn("EDITOR_TRANSACTION_AUDIT_CONTENT", audit_rules)

        kind_violations = self.inspect_fixture(
            "com/opentypeless/android/editor/EditorOperationKind.java",
            """
            package com.opentypeless.android.editor;
            public enum EditorOperationKind {
                INSERT_TEXT,
                ARBITRARY_PAYLOAD
            }
            """,
        )
        self.assertIn(
            "EDITOR_TRANSACTION_AUDIT_KIND_SHAPE",
            {item.rule for item in kind_violations},
        )

    def test_transaction_audit_construction_and_sink_are_exact_host_confined(self):
        external = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadAudit.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.editor.EditorTransactionAudit;
            final class BadAudit {
                EditorTransactionAudit forge(Object source, Object kind, Object result) {
                    return new EditorTransactionAudit(source, kind, result);
                }
            }
            """,
        )
        external_rules = {item.rule for item in external}
        self.assertIn("EDITOR_TRANSACTION_AUDIT_SCOPE_TRANSFER", external_rules)
        self.assertIn("EDITOR_TRANSACTION_AUDIT_CALLER", external_rules)

        malformed_sink = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            import com.opentypeless.android.editor.EditorTransactionAudit;
            final class EditorTransactionManager {
                interface AuditSink {
                    void record(EditorTransactionAudit audit, String body);
                }
                private final AuditSink auditSink = null;
            }
            """,
        )
        self.assertIn(
            "EDITOR_TRANSACTION_AUDIT_SINK",
            {item.rule for item in malformed_sink},
        )

    def test_commit_receipt_and_host_forbid_mutable_recency_lookup_names(self):
        fixtures = (
            (
                "com/opentypeless/android/editor/TransactionReceipt.java",
                """
                package com.opentypeless.android.editor;
                interface TransactionReceipt {
                    Object latest();
                    Object getCurrentCommitRecord();
                }
                """,
            ),
            (
                "com/opentypeless/android/editor/host/CommitLedger.java",
                """
                package com.opentypeless.android.editor.host;
                final class CommitLedger {
                    private final Thread ownerThread = Thread.currentThread();
                    Object peek() { return null; }
                    Object resolve(String commitId,
                            com.opentypeless.android.editor.EditorSessionSnapshot current) {
                        return null;
                    }
                    Object consume(String commitId,
                            com.opentypeless.android.editor.EditorSessionSnapshot current) {
                        return null;
                    }
                }
                """,
            ),
            (
                "com/opentypeless/android/editor/host/EditorTransactionManager.java",
                """
                package com.opentypeless.android.editor.host;
                final class EditorTransactionManager {
                    Object takeLastCommit() { return null; }
                }
                """,
            ),
        )
        for relative_path, source in fixtures:
            with self.subTest(relative_path=relative_path):
                rules = {item.rule for item in self.inspect_fixture(relative_path, source)}
                self.assertIn("COMMIT_RECENCY_LOOKUP_API", rules)

    def test_commit_ledger_source_shape_is_fixed_single_slot_and_exact_id(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/CommitLedger.java",
            """
            package com.opentypeless.android.editor.host;
            import java.util.LinkedHashMap;
            final class CommitLedger {
                private int capacity;
                private final LinkedHashMap<String, Object> records = new LinkedHashMap<>();
                Object resolve() { return null; }
                Object consume(String commitId) { return null; }
            }
            """,
        )
        rules = {item.rule for item in violations}
        self.assertIn("COMMIT_LEDGER_OWNER_CONFINEMENT", rules)
        self.assertIn("COMMIT_LEDGER_SINGLE_SLOT", rules)
        self.assertIn("COMMIT_LEDGER_EXACT_ID_API", rules)

    def test_undo_facade_is_exact_package_confined_and_cannot_accept_authority_envelopes(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            final class EditorTransactionManager {
                public com.opentypeless.android.editor.TransactionReceipt undoCommit(
                        com.opentypeless.android.editor.CommitRecord record,
                        android.view.inputmethod.InputConnection connection) { return null; }
            }
            """,
        )
        self.assertIn("UNDO_FACADE_SHAPE", {item.rule for item in violations})

    def test_raw_restore_facade_is_exact_and_ordinary_apply_cannot_claim_its_source(self):
        malformed = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            final class EditorTransactionManager {
                public com.opentypeless.android.editor.TransactionReceipt restoreRawCommit(
                        com.opentypeless.android.editor.CommitRecord record,
                        String raw,
                        android.view.inputmethod.InputConnection connection) { return null; }
            }
            """,
        )
        self.assertIn(
            "RAW_RESTORE_FACADE_SHAPE", {item.rule for item in malformed}
        )
        self.assertIn("RAW_RESTORE_APPLY_DENIAL", {item.rule for item in malformed})

        bypass = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            final class EditorTransactionManager {
                com.opentypeless.android.editor.EditorTransactionResult restoreRawCommit(
                        String commitId,
                        com.opentypeless.android.editor.EditorSessionSnapshot expectedCurrent,
                        EditorSessionManager.LiveAuthoritySupplier authoritySupplier,
                        EditorSessionManager.UndoEvidenceReader evidenceReader) { return null; }
                Object apply() {
                    return com.opentypeless.android.editor.OperationSource.RAW_RESTORE;
                }
            }
            """,
        )
        self.assertIn("RAW_RESTORE_APPLY_DENIAL", {item.rule for item in bypass})

    def test_replace_last_commit_is_not_an_ordinary_apply_authority(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            final class EditorTransactionManager {
                void apply(com.opentypeless.android.editor.EditorOperation.ReplaceLastCommit forged) {}
            }
            """,
        )
        self.assertIn("UNDO_OPERATION_AUTHORITY", {item.rule for item in violations})

    def test_commit_ledger_exact_id_lookup_cannot_move_outside_transaction_manager(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/UndoShortcut.java",
            """
            package com.opentypeless.android.editor.host;
            final class UndoShortcut {
                Object steal(CommitLedger ledger, String id,
                        com.opentypeless.android.editor.EditorSessionSnapshot current) {
                    return ledger.resolve(id, current);
                }
            }
            """,
        )
        self.assertIn("COMMIT_LEDGER_CALLER", {item.rule for item in violations})

    def test_undo_evidence_capability_is_exact_redacted_and_host_confined(self):
        malformed = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            public final class EditorSessionManager {
                public interface UndoEvidenceReader {
                    Object read(android.view.inputmethod.InputConnection connection);
                }
                public record UndoEvidenceRequest(int length) {}
                public interface UndoEvidenceReadResult {}
                public record UndoEvidence(String text) implements UndoEvidenceReadResult {}
                public record UndoEvidenceUnavailable() implements UndoEvidenceReadResult {}
            }
            """,
        )
        malformed_rules = {item.rule for item in malformed}
        self.assertIn("UNDO_EVIDENCE_SCOPE_SHAPE", malformed_rules)
        self.assertIn("UNDO_EVIDENCE_REDACTION", malformed_rules)

        external = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadUndoEvidenceConsumer.java",
            """
            package com.opentypeless.android.recognition;
            final class BadUndoEvidenceConsumer {
                com.opentypeless.android.editor.host.EditorSessionManager.UndoEvidenceReader reader;
            }
            """,
        )
        self.assertIn(
            "UNDO_EVIDENCE_SCOPE_TRANSFER", {item.rule for item in external}
        )

        ui_external = self.inspect_fixture(
            "com/opentypeless/android/ui/BadUndoActivity.java",
            """
            package com.opentypeless.android.ui;
            final class BadUndoActivity {
                com.opentypeless.android.editor.host.EditorSessionManager.UndoEvidence result;
            }
            """,
        )
        self.assertIn(
            "UNDO_EVIDENCE_SCOPE_TRANSFER", {item.rule for item in ui_external}
        )

    def test_raw_transition_is_owner_bound_redacted_and_host_confined(self):
        malformed = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            public final class EditorSessionManager {
                public enum RawProofState { COMMITTED, ORIGINAL, UNDO, RAW, FORGED }
                public final class RawTransition {
                    android.view.inputmethod.InputConnection retained;
                    @Override public String toString() { return retained.toString(); }
                }
                RawTransition prepareRawTransition(
                        com.opentypeless.android.editor.CommitRecord record,
                        RawProofState targetState) { return null; }
            }
            """,
        )
        self.assertIn(
            "RAW_TRANSITION_SCOPE_SHAPE", {item.rule for item in malformed}
        )

        external = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadRawRestoreConsumer.java",
            """
            package com.opentypeless.android.recognition;
            final class BadRawRestoreConsumer {
                com.opentypeless.android.editor.host.EditorSessionManager.RawTransition token;
            }
            """,
        )
        self.assertIn(
            "RAW_RESTORE_SCOPE_TRANSFER", {item.rule for item in external}
        )

    def test_rolled_back_can_only_be_claimed_by_the_verified_restore_helper(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            import com.opentypeless.android.editor.EditorTransactionResult;
            import com.opentypeless.android.editor.TransactionFailure;
            final class EditorTransactionManager {
                Object claimWithoutRestoring(TransactionFailure failure) {
                    return new EditorTransactionResult.RolledBack(failure);
                }
            }
            """,
        )
        self.assertIn(
            "EDT013_ROLLBACK_AUTHORITY", {item.rule for item in violations}
        )

    def test_current_evidence_requires_absolute_selection_and_remains_host_confined(self):
        malformed = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            public final class EditorSessionManager {
                interface CurrentEvidenceReader {
                    EvidenceReadResult read(android.view.inputmethod.InputConnection connection);
                }
                interface EvidenceReadResult {}
                record CurrentEvidence(String selected) implements EvidenceReadResult {}
                record CurrentEvidenceRequest(int before) {}
                record ValidatedEvidence(String selected, String before, String after) {}
                private record MaterializedEvidence(String selected, String before, String after) {}
                private record EvidenceAttempt(Object value) {}
            }
            """,
        )
        self.assertIn(
            "CURRENT_EVIDENCE_SCOPE_SHAPE", {item.rule for item in malformed}
        )

        external = self.inspect_fixture(
            "com/opentypeless/android/recognition/BadCurrentEvidenceConsumer.java",
            """
            package com.opentypeless.android.recognition;
            final class BadCurrentEvidenceConsumer {
                com.opentypeless.android.editor.host.EditorSessionManager
                        .CurrentEvidenceReader retained;
            }
            """,
        )
        self.assertIn(
            "CURRENT_EVIDENCE_SCOPE_TRANSFER", {item.rule for item in external}
        )

    def test_replace_transition_and_policy_proof_fail_closed_on_shape_or_flow_drift(self):
        malformed = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            public final class EditorSessionManager {
                enum ReplaceProofState { ORIGINAL, INTENDED, FORGED }
                static final class ReplaceTransition {
                    String plaintext;
                    @Override public String toString() { return plaintext; }
                }
                ReplaceTransition prepareReplaceTransition(
                        com.opentypeless.android.editor.EditorSessionSnapshot expected,
                        com.opentypeless.android.editor.EditorOperation.ReplaceSelection operation,
                        ReplaceProofState targetState) { return null; }
            }
            """,
        )
        self.assertIn(
            "REPLACE_TRANSITION_SCOPE_SHAPE", {item.rule for item in malformed}
        )

        missing_policy = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            final class EditorTransactionManager {
                Object apply(com.opentypeless.android.editor.EditorOperation.ReplaceSelection op) {
                    return op.text();
                }
            }
            """,
        )
        self.assertIn(
            "REPLACE_SELECTION_POLICY_PROOF", {item.rule for item in missing_policy}
        )

    def test_transaction_writer_exact_method_surface_rejects_duplicate_commit_helper(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorTransactionManager.java",
            """
            package com.opentypeless.android.editor.host;
            final class EditorTransactionManager {
                static boolean invokeMutator(
                        android.view.inputmethod.InputConnection connection,
                        com.opentypeless.android.editor.EditorOperation operation) {
                    return connection.commitText("one", 1);
                }
                static boolean duplicate(
                        android.view.inputmethod.InputConnection connection) {
                    return connection.commitText("two", 1);
                }
            }
            """,
        )
        rules = {item.rule for item in violations}
        self.assertIn("EDITOR_TRANSACTION_EXACT_WRITE_SURFACE", rules)
        self.assertIn("EDITOR_TRANSACTION_WRITE_METHOD_SURFACE", rules)

    def test_rejects_qualified_android_dependency_without_import(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/AndroidClock.java",
            """
            package com.opentypeless.android.editor;
            final class AndroidClock {
                long now = android.os.SystemClock.elapsedRealtime();
                androidx.lifecycle.ViewModel model;
            }
            """,
        )
        self.assertEqual(
            ["PURE_DOMAIN_ANDROID_DEPENDENCY"], [item.rule for item in violations]
        )
        self.assertIn("android.os.SystemClock.elapsedRealtime", violations[0].detail)
        self.assertIn("androidx.lifecycle.ViewModel", violations[0].detail)

    def test_rejects_editor_domain_import_of_host_registry(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/DomainLeak.java",
            """
            package com.opentypeless.android.editor;
            import com.opentypeless.android.editor.host.InputConnectionRegistry;
            final class DomainLeak { InputConnectionRegistry registry; }
            """,
        )
        self.assertEqual(
            {"EDITOR_HOST_CAPABILITY_BOUNDARY", "PURE_DOMAIN_HOST_DEPENDENCY"},
            {item.rule for item in violations},
        )

    def test_rejects_android_and_host_dependencies_in_editor_subpackage(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/editor/core/DomainLeak.java",
            """
            package com.opentypeless.android.editor.core;
            import android.os.SystemClock;
            import com.opentypeless.android.editor.host.InputConnectionRegistry;
            final class DomainLeak { InputConnectionRegistry registry; }
            """,
        )
        self.assertEqual(
            {
                "EDITOR_HOST_CAPABILITY_BOUNDARY",
                "PURE_DOMAIN_ANDROID_DEPENDENCY",
                "PURE_DOMAIN_HOST_DEPENDENCY",
            },
            {item.rule for item in violations},
        )

    def test_rejects_editor_host_capability_in_unclassified_package_by_default(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/misc/CapabilityLeak.java",
            """
            package com.opentypeless.android.misc;
            import com.opentypeless.android.editor.host.InputConnectionRegistry;
            final class CapabilityLeak { InputConnectionRegistry registry; }
            """,
        )
        self.assertEqual(
            ["EDITOR_HOST_CAPABILITY_BOUNDARY"], [item.rule for item in violations]
        )

    def test_missing_legacy_writer_requires_inventory_update(self):
        with tempfile.TemporaryDirectory() as directory:
            violations = inspect_source_tree(Path(directory), enforce_legacy_inventory=True)
        self.assertEqual(
            len(violations),
            5,
            "the exact legacy writer inventory must be deliberately shrunk",
        )
        self.assertEqual({"EDITOR_WRITE_INVENTORY"}, {item.rule for item in violations})

    def test_keyboard_host_and_facade_are_exact_and_default_deny(self):
        outside = self.inspect_fixture(
            "com/opentypeless/android/actions/KeyboardBypass.java",
            """
            package com.opentypeless.android.actions;
            import com.opentypeless.android.editor.host.EditorSessionManager.KeyboardHost;
            final class KeyboardBypass { KeyboardHost host; }
            """,
        )
        self.assertIn(
            "KEYBOARD_HOST_SCOPE_TRANSFER", {item.rule for item in outside}
        )

        drifted = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            import android.view.inputmethod.EditorInfo;
            import android.view.inputmethod.InputConnection;
            import com.opentypeless.android.editor.EditorSessionSnapshot;
            import com.opentypeless.android.editor.EditorTransactionResult;
            public final class EditorSessionManager {
              public interface KeyboardHost {
                EditorInfo currentEditorInfo();
                InputConnection currentInputConnection();
              }
              public EditorTransactionResult insertKeyboardText(
                  KeyboardHost host, EditorSessionSnapshot expected, String text) { return null; }
            }
            """,
        )
        self.assertIn("KEYBOARD_FACADE_SHAPE", {item.rule for item in drifted})

        legacy = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            import com.opentypeless.android.editor.host.EditorSessionManager;
            final class OpenTypelessImeService
                implements EditorSessionManager.KeyboardHost {
              private void sendEnter() { connection.sendKeyEvent(event); }
            }
            """,
        )
        self.assertIn("KEYBOARD_LEGACY_WRITE_PATH", {item.rule for item in legacy})

    def test_voice_transaction_facade_flag_and_single_writer_are_exact(self):
        drifted = self.inspect_fixture(
            "com/opentypeless/android/editor/host/EditorSessionManager.java",
            """
            package com.opentypeless.android.editor.host;
            import android.view.inputmethod.EditorInfo;
            import android.view.inputmethod.InputConnection;
            import com.opentypeless.android.editor.EditorSessionSnapshot;
            import com.opentypeless.android.editor.EditorTransactionResult;
            public final class EditorSessionManager {
              public interface KeyboardHost {
                EditorInfo currentEditorInfo();
                InputConnection currentInputConnection();
              }
              public EditorTransactionResult setVoiceComposition(
                  KeyboardHost host, EditorSessionSnapshot expected, String text) { return null; }
            }
            """,
        )
        self.assertIn("EDT017_VOICE_FACADE_SHAPE", {item.rule for item in drifted})

        caller = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            import com.opentypeless.android.editor.host.EditorSessionManager;
            final class OpenTypelessImeService
                implements EditorSessionManager.KeyboardHost {
              void applyVoiceTransactionUpdate() { guardedReplace(connection, 0, text, ""); }
            }
            """,
        )
        rules = {item.rule for item in caller}
        self.assertIn("EDT017_VOICE_FACADE_CALLER", rules)
        self.assertIn("EDT017_WRITER_MUTUAL_EXCLUSION", rules)
        self.assertIn("CMP004_VOICE_COORDINATOR_WIRING", rules)
        self.assertIn("CMP005_KEYBOARD_VOICE_PREEMPTION", rules)
        self.assertIn("CMP006_VOICE_LIFECYCLE_CANCELLATION", rules)

        leaked = self.inspect_fixture(
            "com/opentypeless/android/recognition/VoiceCoordinatorLeak.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.editor.CompositionCoordinator;
            final class VoiceCoordinatorLeak {
              CompositionCoordinator.Observation observation;
            }
            """,
        )
        self.assertIn(
            "CMP004_COORDINATOR_SCOPE_TRANSFER", {item.rule for item in leaked}
        )

        flag = self.inspect_fixture(
            "com/opentypeless/android/speech/runtime/VoiceEditorTransactionConfig.java",
            """
            package com.opentypeless.android.speech.runtime;
            final class VoiceEditorTransactionConfig {
              static boolean enabled(Object context) { return false; }
            }
            """,
        )
        self.assertIn("EDT017_FEATURE_FLAG_SHAPE", {item.rule for item in flag})

    def test_voice_engine_v2_requires_canonical_migration_and_sync_rollback(self):
        drifted = self.inspect_fixture(
            "com/opentypeless/android/speech/runtime/VoiceEditorTransactionConfig.java",
            """
            package com.opentypeless.android.speech.runtime;
            import android.content.Context;
            import android.content.SharedPreferences;
            public final class VoiceEditorTransactionConfig {
              private static final String STORE = "voice_editor_transaction_runtime";
              private static final String VOICE_ENGINE_V2 = "voice_engine_v2";
              private VoiceEditorTransactionConfig() {}
              public static boolean enabled(Context context) {
                return preferences(context).getBoolean(VOICE_ENGINE_V2, true);
              }
              public static void setEnabled(Context context, boolean enabled) {
                preferences(context).edit().putBoolean(VOICE_ENGINE_V2, enabled).apply();
              }
              private static SharedPreferences preferences(Context context) { return null; }
            }
            """,
        )
        rules = {item.rule for item in drifted}
        self.assertIn("EDT017_FEATURE_FLAG_SHAPE", rules)
        self.assertIn("VOC011_FEATURE_FLAG_SHAPE", rules)

    def test_voice_lifecycle_cannot_stop_and_wait_for_a_background_final(self):
        unsafe = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            import com.opentypeless.android.editor.host.EditorSessionManager;
            final class OpenTypelessImeService
                implements EditorSessionManager.KeyboardHost {
              private VoiceController voiceController;
              private void stopPipelinePreservingDraft() { voiceController.stop(); }
              public void onFinishInput() { stopPipelinePreservingDraft(); }
              public void onFinishInputView(boolean finishing) {
                stopPipelinePreservingDraft();
              }
              public void onWindowHidden() { stopPipelinePreservingDraft(); }
              public void onDestroy() { stopPipelinePreservingDraft(); }
            }
            """,
        )
        self.assertIn(
            "CMP006_VOICE_LIFECYCLE_CANCELLATION",
            {item.rule for item in unsafe},
        )

    def test_voice_controller_is_capability_free_and_legacy_pipeline_has_one_adapter(self):
        leaked_controller = self.inspect_fixture(
            "com/opentypeless/android/ime/VoiceController.java",
            """
            package com.opentypeless.android.ime;
            import android.database.sqlite.SQLiteDatabase;
            public interface VoiceController {
              enum State { IDLE }
              interface Events { void onError(String value); }
              boolean start(DictationRequest request, Events events);
              void stop();
              void cancel();
              State state();
              SQLiteDatabase database();
            }
            """,
        )
        self.assertIn(
            "VOC001_CONTROLLER_SHAPE", {item.rule for item in leaked_controller}
        )

        drifted_adapter = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipelineAdapter.java",
            """
            package com.opentypeless.android.ime;
            public final class VoicePipelineAdapter implements VoiceController {
              private final VoicePipeline pipeline;
              public VoicePipelineAdapter(VoicePipeline pipeline) { this.pipeline = pipeline; }
              public boolean start(DictationRequest request, Events events) {
                return false;
              }
              public void stop() {}
              public void cancel() {}
              public State state() { return State.IDLE; }
            }
            """,
        )
        self.assertIn(
            "VOC001_PIPELINE_ADAPTER_SHAPE", {item.rule for item in drifted_adapter}
        )

        bypassed_service = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            final class OpenTypelessImeService {
              private VoiceController voiceController;
              private VoicePipeline pipeline;
              void begin(DictationRequest request, VoiceController.Events events) {
                pipeline.start(request, null);
              }
            }
            """,
        )
        self.assertIn(
            "VOC001_CONTROLLER_CALLER", {item.rule for item in bypassed_service}
        )

        bypassed_provider = self.inspect_fixture(
            "com/opentypeless/android/recognition/VoiceBypass.java",
            """
            package com.opentypeless.android.recognition;
            final class VoiceBypass {
              com.opentypeless.android.ime.VoicePipeline pipeline;
              void stop() { pipeline.stopRecording(); }
            }
            """,
        )
        self.assertIn(
            "VOC001_PIPELINE_BYPASS", {item.rule for item in bypassed_provider}
        )

    def test_text_processing_pipeline_is_exact_redacted_and_voice_pipeline_owned(self):
        leaked_surface = self.inspect_fixture(
            "com/opentypeless/android/ime/TextProcessingPipeline.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            public interface TextProcessingPipeline {
              record LlmRequest(String text, InputConnection connection) {}
              String process(String text);
            }
            """,
        )
        rules = {item.rule for item in leaked_surface}
        self.assertIn("VOC003_PIPELINE_SHAPE", rules)
        self.assertIn("VOC003_REQUEST_REDACTION", rules)

        drifted_dispatcher = self.inspect_fixture(
            "com/opentypeless/android/ime/StagedTextProcessingPipeline.java",
            """
            package com.opentypeless.android.ime;
            final class StagedTextProcessingPipeline implements TextProcessingPipeline {
              private final DeterministicStage deterministicStage;
              StagedTextProcessingPipeline(DeterministicStage stage) {
                deterministicStage = stage;
              }
            }
            """,
        )
        self.assertIn(
            "VOC003_STAGED_PIPELINE_SHAPE", {item.rule for item in drifted_dispatcher}
        )

        leaked_request = self.inspect_fixture(
            "com/opentypeless/android/recognition/TextStageConsumer.java",
            """
            package com.opentypeless.android.recognition;
            final class TextStageConsumer {
              com.opentypeless.android.ime.TextProcessingPipeline.LlmRequest request;
            }
            """,
        )
        self.assertIn(
            "VOC003_PIPELINE_SCOPE_TRANSFER", {item.rule for item in leaked_request}
        )

        bypassed_pipeline = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipelineRuntime.java",
            """
            package com.opentypeless.android.ime;
            final class VoicePipelineRuntime {
              private final TextProcessingPipeline textProcessingPipeline = null;
              String finish(String text) { return text; }
            }
            """,
        )
        self.assertIn(
            "VOC003_PIPELINE_CALLER", {item.rule for item in bypassed_pipeline}
        )

    def test_deterministic_personalization_is_independent_exact_stage(self):
        leaked_stage = self.inspect_fixture(
            "com/opentypeless/android/ime/DeterministicPersonalizationStage.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            import com.opentypeless.android.data.PersonalizationSnapshot;
            import com.opentypeless.android.personalization.ProcessingResult;
            public final class DeterministicPersonalizationStage
                    implements TextProcessingPipeline.DeterministicStage {
              private InputConnection retained;
              public ProcessingResult apply(
                      String input,
                      PersonalizationSnapshot personalization,
                      TextProcessingPipeline.DeterministicFailurePolicy policy) {
                return null;
              }
            }
            """,
        )
        self.assertIn(
            "VOC005_PERSONALIZATION_STAGE_SHAPE",
            {item.rule for item in leaked_stage},
        )

        leaked_consumer = self.inspect_fixture(
            "com/opentypeless/android/recognition/PersonalizationBypass.java",
            """
            package com.opentypeless.android.recognition;
            final class PersonalizationBypass {
              com.opentypeless.android.ime.DeterministicPersonalizationStage stage;
            }
            """,
        )
        self.assertIn(
            "VOC003_PIPELINE_SCOPE_TRANSFER",
            {item.rule for item in leaked_consumer},
        )

        direct_binding = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipelineRuntime.java",
            """
            package com.opentypeless.android.ime;
            import com.opentypeless.android.personalization.PersonalizedTextProcessor;
            final class VoicePipelineRuntime {
              private final TextProcessingPipeline textProcessingPipeline = null;
              Object apply(String text, Object snapshot) {
                return PersonalizedTextProcessor.apply(text, null);
              }
            }
            """,
        )
        self.assertIn(
            "VOC005_PIPELINE_BINDING", {item.rule for item in direct_binding}
        )

    def test_llm_and_integrity_implementations_are_independent_exact_stages(self):
        leaked_llm = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenAiOptionalLlmStage.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            import com.opentypeless.android.net.OpenAiCompatibleClient;
            public final class OpenAiOptionalLlmStage
                    implements TextProcessingPipeline.OptionalLlmStage {
              private final OpenAiCompatibleClient client = null;
              private InputConnection retained;
              public String apply(
                      TextProcessingPipeline.LlmRequest request,
                      java.util.function.BooleanSupplier cancelled) {
                try { return request.deterministicText(); }
                catch (RuntimeException error) { return "fallback"; }
              }
            }
            """,
        )
        self.assertIn(
            "VOC006_LLM_STAGE_SHAPE",
            {item.rule for item in leaked_llm},
        )

        leaked_integrity = self.inspect_fixture(
            "com/opentypeless/android/ime/TranscriptIntegrityGuardStage.java",
            """
            package com.opentypeless.android.ime;
            import com.opentypeless.android.net.OpenAiCompatibleClient;
            public final class TranscriptIntegrityGuardStage
                    implements TextProcessingPipeline.IntegrityGuardStage {
              private final String retained = "plaintext";
              public com.opentypeless.android.transform.IntegrityResult apply(
                      TextProcessingPipeline.IntegrityRequest request) {
                return com.opentypeless.android.transform.IntegrityResult.ok();
              }
            }
            """,
        )
        self.assertIn(
            "VOC006_INTEGRITY_STAGE_SHAPE",
            {item.rule for item in leaked_integrity},
        )

        leaked_consumer = self.inspect_fixture(
            "com/opentypeless/android/recognition/LlmStageBypass.java",
            """
            package com.opentypeless.android.recognition;
            final class LlmStageBypass {
              com.opentypeless.android.ime.OpenAiOptionalLlmStage stage;
              com.opentypeless.android.ime.TranscriptIntegrityGuardStage guard;
            }
            """,
        )
        self.assertIn(
            "VOC003_PIPELINE_SCOPE_TRANSFER",
            {item.rule for item in leaked_consumer},
        )

        direct_binding = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipelineRuntime.java",
            """
            package com.opentypeless.android.ime;
            import com.opentypeless.android.transform.TranscriptIntegrityGuard;
            final class VoicePipelineRuntime {
              private final TextProcessingPipeline textProcessingPipeline = null;
              Object finish(TextProcessingPipeline.IntegrityRequest request) {
                return TranscriptIntegrityGuard.validate(
                    request.sourceText(), request.candidateText(),
                    request.mode(), request.personalization());
              }
            }
            """,
        )
        self.assertIn(
            "VOC006_PIPELINE_BINDING", {item.rule for item in direct_binding}
        )

    def test_voice_result_provenance_is_exact_redacted_and_single_source(self):
        leaked_provenance = self.inspect_fixture(
            "com/opentypeless/android/ime/StageProvenance.java",
            """
            package com.opentypeless.android.ime;
            public record StageProvenance(Stage stage, Disposition disposition, String text) {
              enum Stage { RECOGNITION }
              enum Disposition { CAPTURED }
            }
            """,
        )
        self.assertIn(
            "VOC004_PROVENANCE_SHAPE", {item.rule for item in leaked_provenance}
        )

        leaked_result = self.inspect_fixture(
            "com/opentypeless/android/ime/VoiceResult.java",
            """
            package com.opentypeless.android.ime;
            import android.view.inputmethod.InputConnection;
            import java.util.List;
            public record VoiceResult(
                String rawText,
                String deterministicText,
                String candidateText,
                String finalText,
                List<StageProvenance> provenance,
                InputConnection connection) {}
            """,
        )
        result_rules = {item.rule for item in leaked_result}
        self.assertIn("VOC004_RESULT_SHAPE", result_rules)
        self.assertIn("VOC004_RESULT_REDACTION", result_rules)

        duplicated_envelope = self.inspect_fixture(
            "com/opentypeless/android/ime/DictationResult.java",
            """
            package com.opentypeless.android.ime;
            public record DictationResult(
                VoiceResult voiceResult, String rawText, String finalText) {}
            """,
        )
        envelope_rules = {item.rule for item in duplicated_envelope}
        self.assertIn("VOC004_DICTATION_ENVELOPE", envelope_rules)
        self.assertIn("VOC004_RESULT_REDACTION", envelope_rules)

        bypassed_binding = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipelineRuntime.java",
            """
            package com.opentypeless.android.ime;
            final class VoicePipelineRuntime {
              private final TextProcessingPipeline textProcessingPipeline = null;
              VoiceResult finish(String raw) { return VoiceResult.recovered(raw); }
            }
            """,
        )
        self.assertIn(
            "VOC004_PIPELINE_BINDING", {item.rule for item in bypassed_binding}
        )

        legacy_history_read = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            final class OpenTypelessImeService {
              String persist(DictationResult result) { return result.rawText(); }
            }
            """,
        )
        self.assertIn(
            "VOC004_RESULT_CONSUMER", {item.rule for item in legacy_history_read}
        )

    def test_audio_capture_is_exact_and_raw_recorder_types_remain_hidden(self):
        drifted_contract = self.inspect_fixture(
            "com/opentypeless/android/audio/AudioCapture.java",
            """
            package com.opentypeless.android.audio;
            public interface AudioCapture {
              interface Session { boolean active(); }
              AudioRecorder recorder();
            }
            """,
        )
        self.assertIn(
            "VOC002_CAPTURE_SHAPE", {item.rule for item in drifted_contract}
        )

        drifted_adapter = self.inspect_fixture(
            "com/opentypeless/android/audio/AndroidAudioCapture.java",
            """
            package com.opentypeless.android.audio;
            public final class AndroidAudioCapture implements AudioCapture {
              private final AudioRecorder recorder = new AudioRecorder();
              private final RecordingSession shared = new RecordingSession();
            }
            """,
        )
        self.assertIn(
            "VOC002_CAPTURE_ADAPTER_SHAPE", {item.rule for item in drifted_adapter}
        )

        public_raw_recorder = self.inspect_fixture(
            "com/opentypeless/android/audio/AudioRecorder.java",
            """
            package com.opentypeless.android.audio;
            public final class AudioRecorder {
              static int boundedMaximumSeconds(int value) { return value; }
              void record(int maximumSeconds) { boundedMaximumSeconds(maximumSeconds); }
              void stream(int maximumSeconds) { boundedMaximumSeconds(maximumSeconds); }
            }
            """,
        )
        self.assertIn(
            "VOC002_RAW_CAPTURE_SHAPE", {item.rule for item in public_raw_recorder}
        )

        public_raw_session = self.inspect_fixture(
            "com/opentypeless/android/audio/RecordingSession.java",
            """
            package com.opentypeless.android.audio;
            public final class RecordingSession {}
            """,
        )
        self.assertIn(
            "VOC002_RAW_CAPTURE_SHAPE", {item.rule for item in public_raw_session}
        )

        leaked = self.inspect_fixture(
            "com/opentypeless/android/provider/AudioCaptureLeak.java",
            """
            package com.opentypeless.android.provider;
            final class AudioCaptureLeak {
              com.opentypeless.android.audio.AudioCapture capture;
              com.opentypeless.android.audio.AudioRecorder recorder;
              com.opentypeless.android.audio.RecordingSession session;
            }
            """,
        )
        rules = {item.rule for item in leaked}
        self.assertIn("VOC002_CAPTURE_SCOPE_TRANSFER", rules)
        self.assertIn("VOC002_RAW_CAPTURE_BYPASS", rules)

    def test_audio_capture_callers_cannot_restore_raw_or_duplicate_capture_paths(self):
        bypassed_pipeline = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipelineRuntime.java",
            """
            package com.opentypeless.android.ime;
            final class VoicePipelineRuntime {
              private final com.opentypeless.android.audio.AudioRecorder recorder = null;
              private final com.opentypeless.android.audio.AudioCapture audioCapture = null;
              void record() { audioCapture.createSession(false); audioCapture.createSession(true); }
            }
            """,
        )
        rules = {item.rule for item in bypassed_pipeline}
        self.assertIn("VOC002_PIPELINE_BINDING", rules)
        self.assertIn("VOC002_RAW_CAPTURE_BYPASS", rules)

        raw_streaming_engine = self.inspect_fixture(
            "com/opentypeless/android/net/streaming/StreamingRecognitionEngine.java",
            """
            package com.opentypeless.android.net.streaming;
            interface StreamingRecognitionEngine {
              void recognize(
                  com.opentypeless.android.audio.AudioRecorder recorder,
                  com.opentypeless.android.audio.RecordingSession session);
            }
            """,
        )
        streaming_rules = {item.rule for item in raw_streaming_engine}
        self.assertIn("VOC002_STREAMING_ENGINE_SHAPE", streaming_rules)
        self.assertIn("VOC002_RAW_CAPTURE_BYPASS", streaming_rules)

    def test_voice_pipeline_facade_rejects_runtime_leak_bloat_and_public_runtime(self):
        bloated_facade = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipeline.java",
            """
            package com.opentypeless.android.ime;
            public final class VoicePipeline {
              private final VoicePipelineRuntime runtime = null;
              private final com.opentypeless.android.audio.AudioCapture capture = null;
              void cancel() { runtime.cancel(); runtime.cancel(); }
            }
            """,
        )
        self.assertIn(
            "VOC007_FACADE_SHAPE", {item.rule for item in bloated_facade}
        )

        public_runtime = self.inspect_fixture(
            "com/opentypeless/android/ime/VoicePipelineRuntime.java",
            """
            package com.opentypeless.android.ime;
            public final class VoicePipelineRuntime {
              public enum State { IDLE }
            }
            """,
        )
        self.assertIn(
            "VOC007_RUNTIME_SHAPE", {item.rule for item in public_runtime}
        )

        leaked_runtime = self.inspect_fixture(
            "com/opentypeless/android/provider/VoiceRuntimeLeak.java",
            """
            package com.opentypeless.android.provider;
            final class VoiceRuntimeLeak {
              com.opentypeless.android.ime.VoicePipelineRuntime runtime;
            }
            """,
        )
        self.assertIn(
            "VOC007_RUNTIME_SCOPE_TRANSFER", {item.rule for item in leaked_runtime}
        )

    def test_teach_rejects_copied_plaintext_open_factory_and_ineligible_records(self):
        copied_plaintext = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            final class OpenTypelessImeService {
              static final class LastVoiceCommit {
                final String rawText = "";
                final String insertedText = "";
                final String packageName = "";
                final boolean learningAllowed = true;
              }
              private void teachCorrection() {
                LastVoiceCommit commit = null;
                Object intent = commit.rawText + commit.insertedText + commit.packageName;
              }
            }
            """,
        )
        self.assertIn(
            "VOC008_TEACH_AUTHORITY", {item.rule for item in copied_plaintext}
        )

        open_factory = self.inspect_fixture(
            "com/opentypeless/android/provider/TeachLeak.java",
            """
            package com.opentypeless.android.provider;
            final class TeachLeak {
              void launch(Object context, Object record) {
                com.opentypeless.android.HistoryActivity.createTeachIntent(context, record, -1L);
              }
            }
            """,
        )
        self.assertIn(
            "VOC008_TEACH_SCOPE_TRANSFER", {item.rule for item in open_factory}
        )

        weak_activity = self.inspect_fixture(
            "com/opentypeless/android/HistoryActivity.java",
            """
            package com.opentypeless.android;
            public final class HistoryActivity {
              public static Object createTeachIntent(Object raw, Object polished, String scope) {
                return null;
              }
            }
            """,
        )
        self.assertIn(
            "VOC008_TEACH_FACTORY_SHAPE", {item.rule for item in weak_activity}
        )

        weak_resolver = self.inspect_fixture(
            "com/opentypeless/android/personalization/TeachCorrectionResolver.java",
            """
            package com.opentypeless.android.personalization;
            public final class TeachCorrectionResolver {
              public static boolean isEligible(Object record) { return record != null; }
              public static Object resolve(Object stored, Object record) { return stored; }
            }
            """,
        )
        self.assertIn(
            "VOC008_TEACH_RESOLVER_SHAPE", {item.rule for item in weak_resolver}
        )

    def test_rejects_source_symlink(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            root = Path(directory)
            external = Path(outside) / "External.java"
            external.write_text("package example; final class External {}", encoding="utf-8")
            target = root / "External.java"
            try:
                target.symlink_to(external)
            except OSError as error:
                self.skipTest(f"symlinks unavailable: {error}")
            violations = inspect_source_tree(root, enforce_legacy_inventory=False)
        self.assertEqual(["SOURCE_CONTAINMENT"], [item.rule for item in violations])

    def test_cfg001_rejects_open_provider_shapes_raw_secrets_and_execution_types(self):
        provider_violations = self.inspect_fixture(
            "com/opentypeless/android/config/ProviderConfig.java",
            """
            package com.opentypeless.android.config;
            import java.io.Serializable;
            import java.net.HttpURLConnection;
            public interface ProviderConfig extends Serializable {
                record Asr(String id, String apiKey) implements ProviderConfig {}
                HttpURLConnection connect();
            }
            """,
        )
        provider_rules = {item.rule for item in provider_violations}
        self.assertIn("CFG001_DOMAIN_DEPENDENCY", provider_rules)
        self.assertIn("CFG001_PROVIDER_MODEL_SHAPE", provider_rules)
        self.assertIn("CFG001_PROVIDER_VALIDATION", provider_rules)
        self.assertIn("CFG001_SECRET_BOUNDARY", provider_rules)

        secret_violations = self.inspect_fixture(
            "com/opentypeless/android/config/SecretRef.java",
            """
            package com.opentypeless.android.config;
            public record SecretRef(String secretValue) {
                @Override public String toString() { return secretValue; }
            }
            """,
        )
        secret_rules = {item.rule for item in secret_violations}
        self.assertIn("CFG001_SECRET_REF_SHAPE", secret_rules)
        self.assertIn("CFG001_SECRET_BOUNDARY", secret_rules)

    def test_cfg002_rejects_open_unbounded_routes_privacy_gaps_and_authority_leaks(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/config/RecognitionRoute.java",
            """
            package com.opentypeless.android.config;
            import android.content.Context;
            import java.io.Serializable;
            import java.util.List;
            public class RecognitionRoute implements Serializable {
                String apiKey;
                Context context;
                List<Object> steps;
                com.opentypeless.android.diagnostics.RecognitionRoute legacy;
                @Override public String toString() { return apiKey + steps; }
            }
            """,
        )
        rules = {item.rule for item in violations}
        self.assertIn("CFG002_DOMAIN_DEPENDENCY", rules)
        self.assertIn("CFG002_AUTHORITY_BOUNDARY", rules)
        self.assertIn("CFG002_ROUTE_MODEL_SHAPE", rules)
        self.assertIn("CFG002_ROUTE_VALIDATION", rules)
        self.assertIn("CFG002_ROUTE_REDACTION", rules)

    def test_rec001_rejects_partial_name_inferred_capabilities_and_leaky_descriptors(self):
        capability_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/ProviderCapabilities.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            public class ProviderCapabilities {
                Context context;
                String apiKey;
                boolean streaming;
                static ProviderCapabilities declaredForBackend(String providerName) {
                    return providerName.contains("stream")
                            ? new ProviderCapabilities()
                            : new ProviderCapabilities();
                }
            }
            """,
        )
        capability_rules = {item.rule for item in capability_violations}
        self.assertIn("REC001_DOMAIN_DEPENDENCY", capability_rules)
        self.assertIn("REC001_EXPLICIT_DECLARATION", capability_rules)
        self.assertIn("REC001_CAPABILITY_SHAPE", capability_rules)
        self.assertIn("REC001_CAPABILITY_INVARIANTS", capability_rules)

        descriptor_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/ProviderDescriptor.java",
            """
            package com.opentypeless.android.recognition;
            import java.net.URL;
            public record ProviderDescriptor(
                    String name, URL endpoint, String secretValue, Object provider) {
                static ProviderDescriptor declaredForBackend(String providerName) {
                    return null;
                }
                @Override public String toString() {
                    return name + endpoint + secretValue;
                }
            }
            """,
        )
        descriptor_rules = {item.rule for item in descriptor_violations}
        self.assertIn("REC001_DOMAIN_DEPENDENCY", descriptor_rules)
        self.assertIn("REC001_EXPLICIT_DECLARATION", descriptor_rules)
        self.assertIn("REC001_DESCRIPTOR_SHAPE", descriptor_rules)
        self.assertIn("REC001_DESCRIPTOR_REDACTION", descriptor_rules)

    def test_rec002_rejects_open_leaky_events_metadata_and_non_linear_validators(self):
        event_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionEvent.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            public interface RecognitionEvent extends java.io.Serializable {
                record Partial(String sessionId, long sequence, String text, Context context)
                        implements RecognitionEvent {
                    @Override public String toString() { return sessionId + text; }
                }
                record Final(String sessionId, long sequence, String text, String rawError)
                        implements RecognitionEvent {}
            }
            """,
        )
        event_rules = {item.rule for item in event_violations}
        self.assertIn("REC002_DOMAIN_DEPENDENCY", event_rules)
        self.assertIn("REC002_EVENT_SHAPE", event_rules)
        self.assertIn("REC002_EVENT_BOUNDS", event_rules)
        self.assertIn("REC002_EVENT_REDACTION", event_rules)

        metadata_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionMetadata.java",
            """
            package com.opentypeless.android.recognition;
            import java.net.URL;
            public record RecognitionMetadata(
                    String detectedLanguageTag,
                    Float confidence,
                    Long audioDurationMs,
                    String transcript,
                    URL endpoint) {
                @Override public String toString() {
                    return detectedLanguageTag + transcript + endpoint;
                }
            }
            """,
        )
        metadata_rules = {item.rule for item in metadata_violations}
        self.assertIn("REC002_DOMAIN_DEPENDENCY", metadata_rules)
        self.assertIn("REC002_METADATA_SHAPE", metadata_rules)
        self.assertIn("REC002_METADATA_BOUNDS", metadata_rules)

        validator_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionEventValidator.java",
            """
            package com.opentypeless.android.recognition;
            import java.util.ArrayList;
            public class RecognitionEventValidator {
                private final ArrayList<RecognitionEvent> events = new ArrayList<>();
                public boolean accept(RecognitionEvent event) {
                    events.add(event);
                    return true;
                }
                @Override public String toString() { return events.toString(); }
                public enum Disposition { ACCEPTED }
            }
            """,
        )
        validator_rules = {item.rule for item in validator_violations}
        self.assertIn("REC002_DOMAIN_DEPENDENCY", validator_rules)
        self.assertIn("REC002_VALIDATOR_SHAPE", validator_rules)
        self.assertIn("REC002_SEQUENCE_TERMINAL", validator_rules)

    def test_str001_rejects_open_unbounded_wire_code_raw_callers_and_schema_drift(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/net/streaming/StreamingRecognitionWireEvent.java",
            """
            package com.opentypeless.android.net.streaming;
            import java.net.Socket;
            public class StreamingRecognitionWireEvent {
                public static final String PROTOCOL = "streaming.latest";
                private String rawJson;
                public Object decode(String json) { rawJson = json; return json; }
                public String toString() { return rawJson; }
                public final class Stream { String transcript; }
                public interface Result {}
                public record Accepted(String text) implements Result {}
            }
            """,
        )
        rules = {item.rule for item in violations}
        self.assertIn("STR001_DOMAIN_DEPENDENCY", rules)
        self.assertIn("STR001_WIRE_SHAPE", rules)
        self.assertIn("STR001_BOUNDS_VERSION", rules)
        self.assertIn("STR001_SEQUENCE_TERMINAL", rules)
        self.assertIn("STR001_REDACTION", rules)

        outsider = self.inspect_fixture(
            "com/opentypeless/android/net/streaming/UnsafeStreamingProvider.java",
            """
            package com.opentypeless.android.net.streaming;
            final class UnsafeStreamingProvider {
                Object receive(String json) {
                    return StreamingRecognitionWireEvent.decode(json);
                }
            }
            """,
        )
        self.assertIn("STR001_RAW_DECODE_CALLER", {item.rule for item in outsider})

        with tempfile.TemporaryDirectory() as directory:
            android_root = Path(directory)
            schema = android_root / (
                "app/src/main/resources/schemas/"
                "opentypeless-streaming-recognition-event-v1.schema.json"
            )
            schema.parent.mkdir(parents=True)
            schema.write_text(
                '{"$schema":"https://json-schema.org/draft/2020-12/schema",'
                '"$id":"latest","oneOf":[],"$defs":{}}',
                encoding="utf-8",
            )
            schema_rules = {item.rule for item in inspect_android_project(android_root)}
            self.assertIn("STR001_SCHEMA_CONTRACT", schema_rules)

    def test_str002_rejects_open_leaky_unbounded_retrying_streaming_transport(self):
        provider = self.inspect_fixture(
            "com/opentypeless/android/recognition/WebSocketStreamingProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.media.AudioRecord;
            import android.view.inputmethod.InputConnection;
            import java.io.Serializable;
            public class WebSocketStreamingProvider implements Serializable {
                private final String token = "raw-secret";
                private final AudioRecord recorder = null;
                private final InputConnection editor = null;
                private byte[] unboundedAudio;
                void retryForever(Throwable error) {
                    System.out.println(token + error.getMessage());
                }
            }
            """,
        )
        provider_rules = {item.rule for item in provider}
        self.assertIn("STR002_ADAPTER_DEPENDENCY", provider_rules)
        self.assertIn("STR002_PROVIDER_SHAPE", provider_rules)
        self.assertIn("STR002_FRAME_BOUND", provider_rules)
        self.assertIn("STR002_RECONNECT_BOUND", provider_rules)
        self.assertIn("STR002_EVENT_TERMINAL", provider_rules)
        self.assertIn("STR002_CREDENTIAL_BOUNDARY", provider_rules)
        self.assertIn("STR002_FAILURE_REDACTION", provider_rules)

        client = self.inspect_fixture(
            "com/opentypeless/android/net/streaming/StreamingRecognitionWebSocketClient.java",
            """
            package com.opentypeless.android.net.streaming;
            import okhttp3.OkHttpClient;
            public class StreamingRecognitionWebSocketClient {
                private String token;
                StreamingRecognitionWebSocketClient() {
                    new OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .retryOnConnectionFailure(true)
                        .build();
                }
                public Object open(String body) {
                    System.err.println(body + token);
                    return body;
                }
            }
            """,
        )
        client_rules = {item.rule for item in client}
        self.assertIn("STR002_ADAPTER_DEPENDENCY", client_rules)
        self.assertIn("STR002_CLIENT_SHAPE", client_rules)
        self.assertIn("STR002_CLIENT_CONTRACT", client_rules)
        self.assertIn("STR002_CREDENTIAL_BOUNDARY", client_rules)
        self.assertIn("STR002_FAILURE_REDACTION", client_rules)

        outsider = self.inspect_fixture(
            "com/opentypeless/android/recognition/UnsafeStreamingConsumer.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.net.streaming.StreamingRecognitionWebSocketClient;
            final class UnsafeStreamingConsumer {
                private final StreamingRecognitionWebSocketClient client =
                    new StreamingRecognitionWebSocketClient();
                WebSocketStreamingProvider.Backend backend;
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider}
        self.assertIn("STR002_CLIENT_CALLER", outsider_rules)
        self.assertIn("STR002_ADAPTER_SCOPE", outsider_rules)

    def test_str003_rejects_leaky_unbounded_qwen_transport_and_outside_callers(self):
        client = self.inspect_fixture(
            "com/opentypeless/android/net/streaming/Qwen3AsrVllmClient.java",
            """
            package com.opentypeless.android.net.streaming;
            import android.media.AudioRecord;
            import java.io.Serializable;
            import okhttp3.OkHttpClient;
            public class Qwen3AsrVllmClient implements Serializable {
                private final AudioRecord recorder = null;
                Qwen3AsrVllmClient() {
                    new OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .retryOnConnectionFailure(true)
                        .build();
                }
                public Object probe(String body, char[] credential) {
                    System.err.println(body + new String(credential));
                    return body;
                }
            }
            """,
        )
        client_rules = {item.rule for item in client}
        self.assertIn("STR003_ADAPTER_DEPENDENCY", client_rules)
        self.assertIn("STR003_CLIENT_SHAPE", client_rules)
        self.assertIn("STR003_TRANSPORT_BOUND", client_rules)
        self.assertIn("STR003_PROTOCOL_CONTRACT", client_rules)
        self.assertIn("STR003_CREDENTIAL_BOUNDARY", client_rules)
        self.assertIn("STR003_FAILURE_REDACTION", client_rules)

        provider = self.inspect_fixture(
            "com/opentypeless/android/recognition/Qwen3AsrVllmProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.view.inputmethod.InputConnection;
            public class Qwen3AsrVllmProvider {
                private final InputConnection editor = null;
                private String endpoint;
                void probeForever(Throwable error) {
                    System.out.println(endpoint + error.getMessage());
                }
            }
            """,
        )
        provider_rules = {item.rule for item in provider}
        self.assertIn("STR003_ADAPTER_DEPENDENCY", provider_rules)
        self.assertIn("STR003_PROVIDER_SHAPE", provider_rules)
        self.assertIn("STR003_PROBE_BOUND", provider_rules)
        self.assertIn("STR003_PROVIDER_AUTHORITY", provider_rules)
        self.assertIn("STR003_FAILURE_REDACTION", provider_rules)

        outsider = self.inspect_fixture(
            "com/opentypeless/android/recognition/UnsafeQwenConsumer.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.net.streaming.Qwen3AsrVllmClient;
            final class UnsafeQwenConsumer {
                private final Qwen3AsrVllmClient client = new Qwen3AsrVllmClient();
                Qwen3AsrVllmProvider.ProbeWorker worker;
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider}
        self.assertIn("STR003_CLIENT_CALLER", outsider_rules)
        self.assertIn("STR003_ADAPTER_SCOPE", outsider_rules)

    def test_str005_rejects_leaky_unbounded_local_streaming_and_model_drift(self):
        provider = self.inspect_fixture(
            "com/opentypeless/android/recognition/LocalStreamingProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.media.AudioRecord;
            import android.view.inputmethod.InputConnection;
            import java.io.Serializable;
            public class LocalStreamingProvider implements Serializable {
                private final AudioRecord recorder = null;
                private final InputConnection editor = null;
                private byte[] unboundedPcm;
                private String transcript;
                void startForever(Throwable error) {
                    System.out.println(transcript + error.getMessage());
                }
            }
            """,
        )
        provider_rules = {item.rule for item in provider}
        self.assertIn("STR005_ADAPTER_DEPENDENCY", provider_rules)
        self.assertIn("STR005_PROVIDER_SHAPE", provider_rules)
        self.assertIn("STR005_BOUNDED_CAPABILITY", provider_rules)
        self.assertIn("STR005_LIFECYCLE", provider_rules)
        self.assertIn("STR005_EVENT_CONTRACT", provider_rules)
        self.assertIn("STR005_BACKEND_BINDING", provider_rules)
        self.assertIn("STR005_FAILURE_REDACTION", provider_rules)

        client = self.inspect_fixture(
            "com/opentypeless/android/offline/LocalRealtimeRecognitionClient.java",
            """
            package com.opentypeless.android.offline;
            public final class LocalRealtimeRecognitionClient {
                interface Listener { void onPartial(String text); }
                private byte[][] unboundedFrames;
            }
            """,
        )
        self.assertIn(
            "STR005_CLIENT_CONTRACT",
            {item.rule for item in client},
        )

        outsider = self.inspect_fixture(
            "com/opentypeless/android/recognition/UnsafeLocalStreamingConsumer.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.offline.LocalRealtimeRecognitionClient;
            final class UnsafeLocalStreamingConsumer {
                LocalStreamingProvider.Backend backend;
                LocalRealtimeRecognitionClient client;
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider}
        self.assertIn("STR005_PRODUCTION_WIRING", outsider_rules)
        self.assertIn("STR005_CLIENT_CALLER", outsider_rules)

        model = self.inspect_fixture(
            "com/opentypeless/android/offline/OfflineStreamingModelSpec.java",
            """
            package com.opentypeless.android.offline;
            public final class OfflineStreamingModelSpec {
                public static final String REVISION = "latest";
            }
            """,
        )
        self.assertIn("STR005_MODEL_PIN", {item.rule for item in model})

    def test_str006_rejects_leaky_unbounded_double_final_and_production_wiring(self):
        provider = self.inspect_fixture(
            "com/opentypeless/android/recognition/TwoStageStreamingProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.media.AudioRecord;
            import android.view.inputmethod.InputConnection;
            import java.io.Serializable;
            import java.net.URL;
            public class TwoStageStreamingProvider implements Serializable {
                private final AudioRecord recorder = null;
                private final InputConnection editor = null;
                private final URL endpoint = null;
                private byte[] unboundedAudio;
                private String transcript;
                void finishTwice(Throwable error) {
                    System.out.println(transcript + error.getMessage());
                    new RecognitionEvent.Final(null, 1L, transcript, null);
                    new RecognitionEvent.Final(null, 2L, transcript, null);
                }
            }
            """,
        )
        rules = {item.rule for item in provider}
        self.assertIn("STR006_ADAPTER_DEPENDENCY", rules)
        self.assertIn("STR006_PROVIDER_SHAPE", rules)
        self.assertIn("STR006_BOUNDED_AUDIO", rules)
        self.assertIn("STR006_LIFECYCLE", rules)
        self.assertIn("STR006_FINAL_AUTHORITY", rules)
        self.assertIn("STR006_EVENT_CONTRACT", rules)
        self.assertIn("STR006_FAILURE_REDACTION", rules)

        outsider = self.inspect_fixture(
            "com/opentypeless/android/ime/UnsafeTwoStageRegistration.java",
            """
            package com.opentypeless.android.ime;
            final class UnsafeTwoStageRegistration {
                com.opentypeless.android.recognition.TwoStageStreamingProvider provider;
            }
            """,
        )
        self.assertIn(
            "STR006_PRODUCTION_WIRING",
            {item.rule for item in outsider},
        )

    def test_str010_rejects_unbound_router_double_path_and_leaky_controller(self):
        controller = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionRouterVoiceController.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.SharedPreferences;
            import com.opentypeless.android.audio.AudioCapture;
            import com.opentypeless.android.ime.VoiceController;
            public class RecognitionRouterVoiceController implements VoiceController {
                private final VoiceController delegate;
                private final SharedPreferences preferences = null;
                private final AudioCapture capture = null;
                public RecognitionRouterVoiceController(VoiceController delegate) {
                    this.delegate = delegate;
                }
                public boolean start(DictationRequest request, Events events) {
                    String rawMessage = request.toString();
                    events.onError(rawMessage);
                    return delegate.start(request, events);
                }
                public void stop() { delegate.stop(); }
                public void cancel() { delegate.cancel(); }
                public State state() { return delegate.state(); }
            }
            """,
        )
        controller_rules = {item.rule for item in controller}
        self.assertIn("STR010_CONTROLLER_DEPENDENCY", controller_rules)
        self.assertIn("STR010_CONTROLLER_SHAPE", controller_rules)
        self.assertIn("STR010_ROUTE_BINDING", controller_rules)
        self.assertIn("STR010_LIFECYCLE", controller_rules)
        self.assertIn("STR010_REDACTION", controller_rules)

        flag = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionRouterVoiceConfig.java",
            """
            package com.opentypeless.android.recognition;
            import com.opentypeless.android.ime.VoiceController;
            public final class RecognitionRouterVoiceConfig {
                public static VoiceController select(
                        VoiceController legacy,
                        VoiceController router) {
                    legacy.start(null, null);
                    router.start(null, null);
                    return router;
                }
            }
            """,
        )
        flag_rules = {item.rule for item in flag}
        self.assertIn("STR010_FLAG_DEPENDENCY", flag_rules)
        self.assertIn("STR010_FEATURE_FLAG", flag_rules)

        bypass = self.inspect_fixture(
            "com/opentypeless/android/ime/OpenTypelessImeService.java",
            """
            package com.opentypeless.android.ime;
            final class OpenTypelessImeService {
                private VoiceController voiceController;
                private VoicePipeline pipeline;
                void create() {
                    voiceController = new VoicePipelineAdapter(pipeline);
                }
            }
            """,
        )
        self.assertIn(
            "STR010_PRODUCTION_CALLER",
            {item.rule for item in bypass},
        )

        outsider = self.inspect_fixture(
            "com/opentypeless/android/provider/RouterShortcut.java",
            """
            package com.opentypeless.android.provider;
            final class RouterShortcut {
                Object create(android.content.Context context,
                              com.opentypeless.android.ime.VoicePipelineAdapter adapter) {
                    RecognitionRouterVoiceConfig.enabled(context);
                    return new RecognitionRouterVoiceController(context, adapter);
                }
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider}
        self.assertIn("STR010_CONTROLLER_SCOPE", outsider_rules)
        self.assertIn("STR010_FLAG_SCOPE", outsider_rules)

    def test_rec003_rejects_unbounded_overwriting_locked_and_leaky_provider_registries(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/ProviderRegistry.java",
            """
            package com.opentypeless.android.recognition;
            import java.io.Serializable;
            import java.net.URL;
            import java.util.HashMap;
            import java.util.Map;
            public class ProviderRegistry implements Serializable {
                private final Map<String, Object> entries = new HashMap<>();
                private String secretValue;
                public void register(String providerId, Object probe, URL endpoint) {
                    entries.put(providerId, probe);
                }
                public synchronized Object probe(String providerId) {
                    Object callback = entries.get(providerId);
                    return callback.toString();
                }
                @Override public String toString() {
                    return entries.toString() + secretValue;
                }
            }
            """,
        )
        rules = {item.rule for item in violations}
        self.assertIn("REC003_DOMAIN_DEPENDENCY", rules)
        self.assertIn("REC003_REGISTRY_SHAPE", rules)
        self.assertIn("REC003_REGISTRATION_BOUND", rules)
        self.assertIn("REC003_PROBE_LEASE", rules)
        self.assertIn("REC003_RESULT_SHAPE", rules)
        self.assertIn("REC003_REDACTION", rules)

    def test_rec004_rejects_public_off_main_leaky_and_unbounded_system_adapters(self):
        provider_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            public interface RecognitionProvider<R> {
                Context context();
                Object start(R request);
                record Prepared(String descriptor) {}
            }
            """,
        )
        provider_rules = {item.rule for item in provider_violations}
        self.assertIn("REC004_ADAPTER_DEPENDENCY", provider_rules)
        self.assertIn("REC004_PROVIDER_CONTRACT", provider_rules)
        self.assertIn("REC004_FAILURE_REDACTION", provider_rules)

        adapter_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/AndroidSystemRecognitionProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            import com.opentypeless.android.settings.AppSettings;
            import java.net.Socket;
            public final class AndroidSystemRecognitionProvider {
                private final AppSettings settings = null;
                private final String prompt = "secret";
                void start() { backend.start(); }
                void error(String message) { System.out.println(message); }
                @Override public String toString() { return prompt; }
            }
            """,
        )
        adapter_rules = {item.rule for item in adapter_violations}
        self.assertIn("REC004_ADAPTER_DEPENDENCY", adapter_rules)
        self.assertIn("REC004_ADAPTER_SHAPE", adapter_rules)
        self.assertIn("REC004_LEAST_AUTHORITY_REQUEST", adapter_rules)
        self.assertIn("REC004_MAIN_THREAD_LIFECYCLE", adapter_rules)
        self.assertIn("REC004_EVENT_TERMINAL", adapter_rules)
        self.assertIn("REC004_FAILURE_REDACTION", adapter_rules)

        speech_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/SystemSpeechRecognizer.java",
            """
            package com.opentypeless.android.recognition;
            public final class SystemSpeechRecognizer {
                public interface Callback { void onFinal(String text); }
                public void start(Object settings, Callback callback) {}
            }
            """,
        )
        self.assertIn(
            "REC004_SYSTEM_BRIDGE",
            {item.rule for item in speech_violations},
        )

        intent_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/SystemRecognitionIntentFactory.java",
            """
            package com.opentypeless.android.recognition;
            final class SystemRecognitionIntentFactory {
                static Object create(String prompt) { return prompt; }
            }
            """,
        )
        self.assertIn(
            "REC004_SYSTEM_BRIDGE",
            {item.rule for item in intent_violations},
        )

    def test_rec005_rejects_open_leaky_unbounded_and_untyped_upload_adapters(self):
        adapter_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/OpenAiCompatibleUploadProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            import android.view.inputmethod.InputConnection;
            import com.opentypeless.android.security.SecretStore;
            import java.io.Serializable;
            import java.util.concurrent.Executors;
            public class OpenAiCompatibleUploadProvider implements Serializable {
                private final SecretStore secrets = null;
                private final String apiKey = "raw-secret";
                private final byte[] audio = new byte[0];
                private final InputConnection connection = null;
                void start(byte[] wav, String prompt) {
                    Executors.newCachedThreadPool().execute(() -> {
                        System.out.println(prompt + apiKey);
                    });
                }
            }
            """,
        )
        adapter_rules = {item.rule for item in adapter_violations}
        self.assertIn("REC005_ADAPTER_DEPENDENCY", adapter_rules)
        self.assertIn("REC005_ADAPTER_SHAPE", adapter_rules)
        self.assertIn("REC005_REQUEST_BOUND", adapter_rules)
        self.assertIn("REC005_CREDENTIAL_BOUNDARY", adapter_rules)
        self.assertIn("REC005_LIFECYCLE", adapter_rules)
        self.assertIn("REC005_EVENT_TERMINAL", adapter_rules)
        self.assertIn("REC005_FAILURE_REDACTION", adapter_rules)

        client_violations = self.inspect_fixture(
            "com/opentypeless/android/net/OpenAiCompatibleClient.java",
            """
            package com.opentypeless.android.net;
            import java.net.HttpURLConnection;
            public class OpenAiCompatibleClient {
                public String transcribe(byte[] audio, String key) throws Exception {
                    HttpURLConnection connection = null;
                    connection.setInstanceFollowRedirects(true);
                    return connection.getErrorStream().toString() + key;
                }
            }
            """,
        )
        client_rules = {item.rule for item in client_violations}
        self.assertIn("REC005_ADAPTER_DEPENDENCY", client_rules)
        self.assertIn("REC005_CLIENT_CONTRACT", client_rules)

    def test_rec006_rejects_leaky_unbounded_local_adapters_and_scope_escape(self):
        adapter_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/SenseVoiceFinalProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            import android.view.inputmethod.InputConnection;
            import java.io.File;
            import java.io.Serializable;
            import java.util.concurrent.Executors;
            public class SenseVoiceFinalProvider implements Serializable {
                private final byte[] audio = new byte[0];
                private final String modelPath = "/secret/model.onnx";
                private final InputConnection connection = null;
                void start(String transcript) {
                    Executors.newCachedThreadPool().execute(() -> {
                        System.out.println(transcript + modelPath);
                    });
                }
            }
            """,
        )
        adapter_rules = {item.rule for item in adapter_violations}
        self.assertIn("REC006_ADAPTER_DEPENDENCY", adapter_rules)
        self.assertIn("REC006_ADAPTER_SHAPE", adapter_rules)
        self.assertIn("REC006_REQUEST_BOUND", adapter_rules)
        self.assertIn("REC006_LIFECYCLE", adapter_rules)
        self.assertIn("REC006_EVENT_TERMINAL", adapter_rules)
        self.assertIn("REC006_AVAILABILITY_MAPPING", adapter_rules)
        self.assertIn("REC006_CLIENT_BINDING", adapter_rules)
        self.assertIn("REC006_FAILURE_REDACTION", adapter_rules)

        recognizer_violations = self.inspect_fixture(
            "com/opentypeless/android/offline/LocalOfflineRecognizer.java",
            """
            package com.opentypeless.android.offline;
            import android.content.Context;
            public final class LocalOfflineRecognizer {
                public static boolean isSupportedDevice(Context context) { return true; }
            }
            """,
        )
        recognizer_rules = {item.rule for item in recognizer_violations}
        self.assertIn("REC006_ADAPTER_DEPENDENCY", recognizer_rules)
        self.assertIn("REC006_DEVICE_SUPPORT", recognizer_rules)

        client_violations = self.inspect_fixture(
            "com/opentypeless/android/offline/LocalOfflineRecognitionClient.java",
            """
            package com.opentypeless.android.offline;
            public final class LocalOfflineRecognitionClient {
                public record Result(String exactText, String punctuatedText) {
                    @Override public String toString() {
                        return exactText + punctuatedText;
                    }
                }
            }
            """,
        )
        client_rules = {item.rule for item in client_violations}
        self.assertIn("REC006_ADAPTER_DEPENDENCY", client_rules)
        self.assertIn("REC006_CLIENT_RESULT", client_rules)

        scope_violations = self.inspect_fixture(
            "com/opentypeless/android/ime/LeakyLocalProviderConsumer.java",
            """
            package com.opentypeless.android.ime;
            final class LeakyLocalProviderConsumer {
                com.opentypeless.android.recognition.SenseVoiceFinalProvider.Backend backend;
            }
            """,
        )
        self.assertIn(
            "REC006_ADAPTER_SCOPE",
            {item.rule for item in scope_violations},
        )

    def test_rec007_rejects_fake_streaming_unbounded_replay_and_scope_escape(self):
        adapter_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/PrefixReplayPreviewProvider.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            import android.view.inputmethod.InputConnection;
            import java.io.Serializable;
            import java.util.concurrent.Executors;
            public class PrefixReplayPreviewProvider implements Serializable {
                InputConnection connection;
                byte[] unboundedAudio;
                String transcript;
                void start() {
                    Executors.newCachedThreadPool().execute(() -> {
                        System.out.println(transcript);
                        new RecognitionEvent.Final(null, 1L, transcript, null);
                    });
                }
            }
            """,
        )
        adapter_rules = {item.rule for item in adapter_violations}
        self.assertIn("REC007_ADAPTER_DEPENDENCY", adapter_rules)
        self.assertIn("REC007_ADAPTER_SHAPE", adapter_rules)
        self.assertIn("REC007_CAPABILITY_DECLARATION", adapter_rules)
        self.assertIn("REC007_REQUEST_BOUND", adapter_rules)
        self.assertIn("REC007_LIFECYCLE", adapter_rules)
        self.assertIn("REC007_EVENT_CONTRACT", adapter_rules)
        self.assertIn("REC007_PCM_BOUND", adapter_rules)
        self.assertIn("REC007_BACKEND_BINDING", adapter_rules)
        self.assertIn("REC007_FAILURE_REDACTION", adapter_rules)

        preview_violations = self.inspect_fixture(
            "com/opentypeless/android/offline/LocalRealtimePreview.java",
            """
            package com.opentypeless.android.offline;
            import java.util.concurrent.Executors;
            public final class LocalRealtimePreview {
                byte[] audio = new byte[Integer.MAX_VALUE];
                void accept(byte[] pcm) {
                    Executors.newCachedThreadPool().execute(() -> System.out.println(pcm));
                }
            }
            """,
        )
        preview_rules = {item.rule for item in preview_violations}
        self.assertIn("REC007_ADAPTER_DEPENDENCY", preview_rules)
        self.assertIn("REC007_LEGACY_PREVIEW_BOUND", preview_rules)
        self.assertIn("REC007_FAILURE_REDACTION", preview_rules)

        scope_violations = self.inspect_fixture(
            "com/opentypeless/android/ime/LeakyPrefixConsumer.java",
            """
            package com.opentypeless.android.ime;
            final class LeakyPrefixConsumer {
                com.opentypeless.android.recognition.PrefixReplayPreviewProvider.Backend backend;
            }
            """,
        )
        self.assertIn(
            "REC007_ADAPTER_SCOPE",
            {item.rule for item in scope_violations},
        )

    def test_rec008_rejects_split_leaky_failure_mapping_and_scope_escape(self):
        mapper_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionFailureMapper.java",
            """
            package com.opentypeless.android.recognition;
            public final class RecognitionFailureMapper {
                private String rawMessage;
                public String fromAndroidSystem(int code, String message) {
                    rawMessage = message;
                    System.out.println(message);
                    return message;
                }
            }
            """,
        )
        mapper_rules = {item.rule for item in mapper_violations}
        self.assertIn("REC008_FAILURE_DEPENDENCY", mapper_rules)
        self.assertIn("REC008_MAPPER_SHAPE", mapper_rules)
        self.assertIn("REC008_FAILURE_REDACTION", mapper_rules)

        legacy_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionFailure.java",
            """
            package com.opentypeless.android.recognition;
            public record RecognitionFailure(int errorCode, String message) {
                @Override public String toString() { return message; }
            }
            """,
        )
        legacy_rules = {item.rule for item in legacy_violations}
        self.assertIn("REC008_FAILURE_DEPENDENCY", legacy_rules)
        self.assertIn("REC008_LEGACY_FAILURE_SHAPE", legacy_rules)

        provider_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/AndroidSystemRecognitionProvider.java",
            """
            package com.opentypeless.android.recognition;
            final class AndroidSystemRecognitionProvider {
                Object failureClass(int code, String message) {
                    return code == 1 ? "network" : "server";
                }
            }
            """,
        )
        self.assertIn(
            "REC008_PROVIDER_DELEGATION",
            {item.rule for item in provider_violations},
        )

        scope_violations = self.inspect_fixture(
            "com/opentypeless/android/provider/LeakyFailureMapperConsumer.java",
            """
            package com.opentypeless.android.provider;
            final class LeakyFailureMapperConsumer {
                Object classify(Throwable error) {
                    return com.opentypeless.android.recognition.RecognitionFailureMapper
                            .fromUpload(error);
                }
            }
            """,
        )
        self.assertIn(
            "REC008_MAPPER_SCOPE",
            {item.rule for item in scope_violations},
        )

    def test_rec009_rejects_executable_unbounded_router_and_route_lease_escape(self):
        router_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionRouter.java",
            """
            package com.opentypeless.android.recognition;
            import android.content.Context;
            import java.io.Serializable;
            public class RecognitionRouter implements Serializable {
                Context context;
                String transcript;
                RecognitionProvider<?> provider;
                Object start() {
                    System.out.println(transcript);
                    return provider;
                }
            }
            """,
        )
        router_rules = {item.rule for item in router_violations}
        self.assertIn("REC009_ROUTER_DEPENDENCY", router_rules)
        self.assertIn("REC009_ROUTER_SHAPE", router_rules)
        self.assertIn("REC009_ROUTE_POLICY", router_rules)
        self.assertIn("REC009_ROUTER_REDACTION", router_rules)
        self.assertIn("REC010_CONFIRMATION_SHAPE", router_rules)
        self.assertIn("REC010_CONFIRMATION_POLICY", router_rules)
        self.assertIn("REC010_CONFIRMATION_REDACTION", router_rules)

        registry_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/ProviderRegistry.java",
            """
            package com.opentypeless.android.recognition;
            final class ProviderRegistry {
                public Object routeLease(String providerId) { return providerId; }
                public boolean isCurrent(Object lease) { return true; }
            }
            """,
        )
        self.assertIn(
            "REC009_REGISTRY_LEASE",
            {item.rule for item in registry_violations},
        )

        scope_violations = self.inspect_fixture(
            "com/opentypeless/android/provider/LeakyRouterConsumer.java",
            """
            package com.opentypeless.android.provider;
            final class LeakyRouterConsumer {
                RecognitionRouter router;
                RecognitionRouter.PrivacyAuthorization authorization;
                RecognitionRouter.ConfirmationDecision decision;
                ProviderRegistry.RouteLease lease;
                Object acquire(ProviderRegistry registry) {
                    return registry.routeLease("secret.provider");
                }
                Object approve(Object request) {
                    return router.onConfirmation(request, decision);
                }
            }
            """,
        )
        scope_rules = {item.rule for item in scope_violations}
        self.assertIn("REC009_ROUTER_SCOPE", scope_rules)
        self.assertIn("REC009_LEASE_SCOPE", scope_rules)
        self.assertIn("REC009_LEASE_CALLER", scope_rules)
        self.assertIn("REC010_CONFIRMATION_SCOPE", scope_rules)
        self.assertIn("REC010_CONFIRMATION_CALLER", scope_rules)

    def test_rec011_rejects_open_breaker_identity_leaks_and_outside_callers(self):
        breaker_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/ProviderCircuitBreaker.java",
            """
            package com.opentypeless.android.recognition;
            import java.io.Serializable;
            public class ProviderCircuitBreaker implements Serializable {
                static final int FAILURE_THRESHOLD = 99;
                ProviderDescriptor retained;
                String secret;
                public Object acquire(ProviderDescriptor descriptor) {
                    retained = descriptor;
                    return descriptor;
                }
                @Override public String toString() {
                    return retained.id() + secret;
                }
            }
            """,
        )
        breaker_rules = {item.rule for item in breaker_violations}
        self.assertIn("REC011_BREAKER_SHAPE", breaker_rules)
        self.assertIn("REC011_BREAKER_POLICY", breaker_rules)
        self.assertIn("REC011_BREAKER_REDACTION", breaker_rules)

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/provider/BreakerShortcut.java",
            """
            package com.opentypeless.android.provider;
            final class BreakerShortcut {
                ProviderCircuitBreaker circuitBreaker;
                Object bypass(ProviderDescriptor descriptor) {
                    return circuitBreaker.acquire(descriptor);
                }
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider_violations}
        self.assertIn("REC011_BREAKER_SCOPE", outsider_rules)
        self.assertIn("REC011_BREAKER_CALLER", outsider_rules)

        router_violations = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionRouter.java",
            """
            package com.opentypeless.android.recognition;
            final class RecognitionRouter {
                Object start() { return new Object(); }
            }
            """,
        )
        router_rules = {item.rule for item in router_violations}
        self.assertIn("REC011_ROUTER_SHAPE", router_rules)
        self.assertIn("REC011_ROUTER_BINDING", router_rules)

    def test_rec012_rejects_leaky_results_wrapping_generations_and_lifecycle_escape(self):
        support = self.inspect_fixture(
            "com/opentypeless/android/recognition/SystemRecognitionSupport.java",
            """
            package com.opentypeless.android.recognition;
            public final class SystemRecognitionSupport {
                public record Result(String message, int errorCode) {}
                public record DownloadResult(String message, int errorCode) {}
            }
            """,
        )
        support_rules = {item.rule for item in support}
        self.assertIn("REC012_RESULT_SHAPE", support_rules)
        self.assertIn("REC012_OPERATION_POLICY", support_rules)

        api33 = self.inspect_fixture(
            "com/opentypeless/android/recognition/SystemRecognitionSupportApi33.java",
            """
            package com.opentypeless.android.recognition;
            final class SystemRecognitionSupportApi33 {
                Object check(Object settings, Object snapshot, RuntimeException failure) {
                    String leaked = failure.getMessage();
                    return SystemRecognitionIntentFactory.create(settings, snapshot);
                }
            }
            """,
        )
        self.assertIn("REC012_API33_BINDING", {item.rule for item in api33})

        coordinator = self.inspect_fixture(
            "com/opentypeless/android/recognition/SystemModelDownloadCoordinator.java",
            """
            package com.opentypeless.android.recognition;
            import java.util.WeakHashMap;
            public final class SystemModelDownloadCoordinator {
                static long generation;
                static long next() { return ++generation; }
                static String safeMessage(RuntimeException failure) {
                    return failure.getMessage();
                }
                static void addListener(Object listener) {}
            }
            """,
        )
        self.assertIn("REC012_COORDINATOR_POLICY", {item.rule for item in coordinator})

        evaluator = self.inspect_fixture(
            "com/opentypeless/android/recognition/RecognitionLanguageSupportEvaluator.java",
            """
            package com.opentypeless.android.recognition;
            final class RecognitionLanguageSupportEvaluator {
                static boolean contains(java.util.List<String> values, String requested) {
                    return values.contains(requested);
                }
            }
            """,
        )
        self.assertIn("REC012_EVALUATOR_BOUNDS", {item.rule for item in evaluator})

        outsider = self.inspect_fixture(
            "com/opentypeless/android/provider/ModelDownloadShortcut.java",
            """
            package com.opentypeless.android.provider;
            final class ModelDownloadShortcut {
                Object state() { return SystemModelDownloadCoordinator.snapshot(); }
                Object intent() { return SystemRecognitionIntentFactory.createCapabilityRequest(null); }
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider}
        self.assertIn("REC012_COORDINATOR_SCOPE", outsider_rules)
        self.assertIn("REC012_CAPABILITY_CALLER", outsider_rules)

    def test_cfg003_rejects_collapsed_states_open_codecs_and_persistence_authority(self):
        model_violations = self.inspect_fixture(
            "com/opentypeless/android/config/OverrideValue.java",
            """
            package com.opentypeless.android.config;
            import android.content.Context;
            import java.io.Serializable;
            public interface OverrideValue<T> extends Serializable {
                Context context();
                record Value<T>(T value) implements OverrideValue<T> {
                    @Override public String toString() { return "value=" + value; }
                }
            }
            """,
        )
        model_rules = {item.rule for item in model_violations}
        self.assertIn("CFG003_DOMAIN_DEPENDENCY", model_rules)
        self.assertIn("CFG003_OVERRIDE_VALUE_SHAPE", model_rules)
        self.assertIn("CFG003_OVERRIDE_REDACTION", model_rules)

        codec_violations = self.inspect_fixture(
            "com/opentypeless/android/config/OverrideValueCodec.java",
            """
            package com.opentypeless.android.config;
            import android.database.sqlite.SQLiteDatabase;
            public class OverrideValueCodec<T> {
                SQLiteDatabase database;
                String encodedValue;
                public record DbRow(String value) {}
                @Override public String toString() { return encodedValue; }
            }
            """,
        )
        codec_rules = {item.rule for item in codec_violations}
        self.assertIn("CFG003_DOMAIN_DEPENDENCY", codec_rules)
        self.assertIn("CFG003_CODEC_SHAPE", codec_rules)
        self.assertIn("CFG003_CODEC_REDACTION", codec_rules)

    def test_cfg004_rejects_nullable_maps_legacy_settings_and_unbounded_matchers(self):
        violations = self.inspect_fixture(
            "com/opentypeless/android/config/GlobalConfig.java",
            """
            package com.opentypeless.android.config;
            import android.content.Context;
            import com.opentypeless.android.settings.AppSettings;
            import java.io.Serializable;
            import java.util.Map;
            public class GlobalConfig implements Serializable {
                Context context;
                AppSettings legacy;
                Map<String, Object> values;
                String packageName;
                @Override public String toString() { return packageName + values; }
            }
            """,
        )
        rules = {item.rule for item in violations}
        self.assertIn("CFG004_DOMAIN_DEPENDENCY", rules)
        self.assertIn("CFG004_MODEL_SHAPE", rules)
        self.assertIn("CFG004_REDACTION", rules)

        override_violations = self.inspect_fixture(
            "com/opentypeless/android/config/RuleOverrides.java",
            """
            package com.opentypeless.android.config;
            public record RuleOverrides(String route, Boolean sendContext) {}
            """,
        )
        override_rules = {item.rule for item in override_violations}
        self.assertIn("CFG004_MODEL_SHAPE", override_rules)
        self.assertIn("CFG004_VALIDATION", override_rules)

    def test_cfg005_rejects_open_results_wrong_precedence_and_unbounded_authority(self):
        result_violations = self.inspect_fixture(
            "com/opentypeless/android/config/EffectiveProfile.java",
            """
            package com.opentypeless.android.config;
            import java.io.Serializable;
            import java.util.Map;
            public record EffectiveProfile(Map<String, Object> raw)
                    implements Serializable {
                public static Object resolved(Object value) { return value; }
                @Override public String toString() { return "raw=" + raw; }
            }
            """,
        )
        result_rules = {item.rule for item in result_violations}
        self.assertIn("CFG005_DOMAIN_DEPENDENCY", result_rules)
        self.assertIn("CFG005_RESULT_SHAPE", result_rules)
        self.assertIn("CFG005_REDACTION", result_rules)

        resolver_violations = self.inspect_fixture(
            "com/opentypeless/android/config/EffectiveProfileResolver.java",
            """
            package com.opentypeless.android.config;
            import android.content.Context;
            import java.util.List;
            public class EffectiveProfileResolver {
                Context context;
                List<Object> unbounded;
                Object resolve(Object provider, Object global, Object app,
                               Object field, Object session) {
                    return provider;
                }
                @Override public String toString() { return "rules=" + unbounded; }
            }
            """,
        )
        resolver_rules = {item.rule for item in resolver_violations}
        self.assertIn("CFG005_DOMAIN_DEPENDENCY", resolver_rules)
        self.assertIn("CFG005_RESOLVER_SHAPE", resolver_rules)
        self.assertIn("CFG005_PRECEDENCE", resolver_rules)
        self.assertIn("CFG005_INPUT_BOUNDS", resolver_rules)
        self.assertIn("CFG005_REDACTION", resolver_rules)

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/config/ResolverBypass.java",
            """
            package com.opentypeless.android.config;
            final class ResolverBypass {
                Object forge() { return EffectiveProfile.resolved(null, null, null); }
            }
            """,
        )
        self.assertIn(
            "CFG005_RESOLUTION_AUTHORITY",
            {item.rule for item in outsider_violations},
        )

    def test_cfg006_rejects_secret_copy_partial_async_and_outside_migration_authority(self):
        migration_violations = self.inspect_fixture(
            "com/opentypeless/android/settings/LegacyAppSettingsMigration.java",
            """
            package com.opentypeless.android.settings;
            import android.content.Context;
            import android.content.SharedPreferences;
            import com.opentypeless.android.config.SecretRef;
            public class LegacyAppSettingsMigration {
                String sttApiKey;
                SecretRef secret;
                void migrate(Context context, SharedPreferences preferences) {
                    context.getSharedPreferences("other", 0).edit()
                            .remove("recognition_backend")
                            .putString("stt_api_key", sttApiKey)
                            .apply();
                }
                @Override public String toString() { return sttApiKey; }
            }
            """,
        )
        rules = {item.rule for item in migration_violations}
        self.assertIn("CFG006_MIGRATION_DEPENDENCY", rules)
        self.assertIn("CFG006_MIGRATION_SHAPE", rules)
        self.assertIn("CFG006_EXACT_MAPPING", rules)
        self.assertIn("CFG006_ATOMIC_PERSISTENCE", rules)
        self.assertIn("CFG006_SECRET_BOUNDARY", rules)
        self.assertIn("CFG006_REDACTION", rules)

        repository_violations = self.inspect_fixture(
            "com/opentypeless/android/settings/SettingsRepository.java",
            """
            package com.opentypeless.android.settings;
            final class SettingsRepository {
                Object loadMigratedGlobalConfig() { return null; }
            }
            """,
        )
        self.assertIn(
            "CFG006_REPOSITORY_WIRING",
            {item.rule for item in repository_violations},
        )

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/ime/ConfigMigrationShortcut.java",
            """
            package com.opentypeless.android.ime;
            final class ConfigMigrationShortcut {
                Object migrate() { return LegacyAppSettingsMigration.migrate(null, null); }
            }
            """,
        )
        self.assertIn(
            "CFG006_MIGRATION_AUTHORITY",
            {item.rule for item in outsider_violations},
        )

    def test_cfg007_rejects_false_drift_unmapped_copy_async_and_outside_authority(self):
        migration_violations = self.inspect_fixture(
            "com/opentypeless/android/settings/LegacyAppProfileMigration.java",
            """
            package com.opentypeless.android.settings;
            import android.content.Context;
            import android.content.SharedPreferences;
            import com.opentypeless.android.config.AppRule;
            import com.opentypeless.android.config.SecretRef;
            public class LegacyAppProfileMigration {
                String customInstructions;
                SecretRef secret;
                void migrate(Context context, SharedPreferences preferences) {
                    context.getSharedPreferences("other", 0).edit()
                            .remove("profiles_v1")
                            .putString("app_rules_v1_rules", customInstructions)
                            .apply();
                }
                @Override public String toString() { return customInstructions; }
            }
            """,
        )
        rules = {item.rule for item in migration_violations}
        self.assertIn("CFG007_MIGRATION_DEPENDENCY", rules)
        self.assertIn("CFG007_MIGRATION_SHAPE", rules)
        self.assertIn("CFG007_EXACT_MAPPING", rules)
        self.assertIn("CFG007_ATOMIC_PERSISTENCE", rules)
        self.assertIn("CFG007_UNMAPPED_DATA_BOUNDARY", rules)
        self.assertIn("CFG007_REDACTION", rules)

        repository_violations = self.inspect_fixture(
            "com/opentypeless/android/settings/AppProfileRepository.java",
            """
            package com.opentypeless.android.settings;
            final class AppProfileRepository {
                Object loadMigratedAppRules() { return null; }
            }
            """,
        )
        self.assertIn(
            "CFG007_REPOSITORY_WIRING",
            {item.rule for item in repository_violations},
        )

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/ime/AppRuleMigrationShortcut.java",
            """
            package com.opentypeless.android.ime;
            final class AppRuleMigrationShortcut {
                Object migrate() { return LegacyAppProfileMigration.migrate(null); }
            }
            """,
        )
        self.assertIn(
            "CFG007_MIGRATION_AUTHORITY",
            {item.rule for item in outsider_violations},
        )

    def test_cfg008_rejects_plaintext_open_store_async_migration_and_outside_bridge(self):
        store_violations = self.inspect_fixture(
            "com/opentypeless/android/security/SecretStore.java",
            """
            package com.opentypeless.android.security;
            import android.content.Context;
            import android.os.Bundle;
            import android.util.Log;
            import com.opentypeless.android.config.SecretRef;
            import java.io.Serializable;
            public class SecretStore implements Serializable {
                String plaintext;
                public String getString(SecretRef ref) { return plaintext; }
                public void exportSecret(Bundle bundle) {
                    bundle.putString("secret", plaintext);
                    Log.d("secret", plaintext);
                }
                public void migrateLegacy() { new SecurePreferences(null).put("x", plaintext); }
            }
            """,
        )
        rules = {item.rule for item in store_violations}
        self.assertIn("CFG008_SECRET_STORE_DEPENDENCY", rules)
        self.assertIn("CFG008_SECRET_STORE_SHAPE", rules)
        self.assertIn("CFG008_SECRET_LIFECYCLE", rules)
        self.assertIn("CFG008_LEGACY_MIGRATION", rules)
        self.assertIn("CFG008_SECRET_EXFILTRATION", rules)
        self.assertIn("CFG008_SECRET_REDACTION", rules)

        repository_violations = self.inspect_fixture(
            "com/opentypeless/android/settings/SettingsRepository.java",
            """
            package com.opentypeless.android.settings;
            import com.opentypeless.android.security.SecretStore;
            public final class SettingsRepository {
                public Object loadMigratedSecretRefs() { return null; }
            }
            """,
        )
        self.assertIn(
            "CFG008_REPOSITORY_WIRING",
            {item.rule for item in repository_violations},
        )

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/net/SecretShortcut.java",
            """
            package com.opentypeless.android.net;
            final class SecretShortcut {
                Object leak(SecretStore store) {
                    return store.storedLegacyValue(null);
                }
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider_violations}
        self.assertIn("CFG008_SECRET_STORE_AUTHORITY", outsider_rules)
        self.assertIn("CFG008_LEGACY_BRIDGE_CALLER", outsider_rules)

    def test_cfg009_rejects_broad_visibility_unbounded_models_and_outside_catalog_callers(self):
        with tempfile.TemporaryDirectory() as directory:
            android_root = Path(directory)
            manifest = android_root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                  <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
                </manifest>
                """,
                encoding="utf-8",
            )
            manifest_rules = {
                item.rule for item in inspect_android_project(android_root)
            }
            self.assertIn("CFG009_BROAD_PACKAGE_VISIBILITY", manifest_rules)

        model_violations = self.inspect_fixture(
            "com/opentypeless/android/config/AppPickerModel.java",
            """
            package com.opentypeless.android.config;
            import android.content.Context;
            import java.io.Serializable;
            import java.util.List;
            public class AppPickerModel implements Serializable {
                Context context;
                public List<Object> entries() { return null; }
                public String toString() { return "com.private.application"; }
            }
            """,
        )
        model_rules = {item.rule for item in model_violations}
        self.assertIn("CFG009_MODEL_DEPENDENCY", model_rules)
        self.assertIn("CFG009_MODEL_SHAPE", model_rules)

        catalog_violations = self.inspect_fixture(
            "com/opentypeless/android/InstalledAppCatalog.java",
            """
            package com.opentypeless.android;
            import android.Manifest;
            import android.content.Context;
            import android.content.pm.LauncherApps;
            import android.util.Log;
            final class InstalledAppCatalog {
                Object load(Context context) {
                    Log.d("apps", "installed");
                    context.getPackageManager().getInstalledApplications(0);
                    return context.getSystemService(LauncherApps.class).getProfiles();
                }
            }
            """,
        )
        catalog_rules = {item.rule for item in catalog_violations}
        self.assertIn("CFG009_BROAD_PACKAGE_VISIBILITY", catalog_rules)
        self.assertIn("CFG009_CATALOG_SHAPE", catalog_rules)

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/net/AppInventoryUpload.java",
            """
            package com.opentypeless.android.net;
            import android.content.Context;
            import android.content.pm.LauncherApps;
            import com.opentypeless.android.config.AppPickerModel;
            final class AppInventoryUpload {
                AppPickerModel leakedInventory;
                Object collect(Context context) {
                    InstalledAppCatalog.load(context);
                    return context.getSystemService(LauncherApps.class);
                }
            }
            """,
        )
        outsider_rules = {item.rule for item in outsider_violations}
        self.assertIn("CFG009_CATALOG_AUTHORITY", outsider_rules)
        self.assertIn("CFG009_LAUNCHER_APPS_AUTHORITY", outsider_rules)
        self.assertIn("CFG009_INVENTORY_EXFILTRATION", outsider_rules)

        activity_violations = self.inspect_fixture(
            "com/opentypeless/android/AppProfileActivity.java",
            """
            package com.opentypeless.android;
            public final class AppProfileActivity {
                String packageName;
            }
            """,
        )
        self.assertIn(
            "CFG009_ACTIVITY_WIRING",
            {item.rule for item in activity_violations},
        )

    def test_cfg010_rejects_priority_recomputation_open_values_and_resolver_vocabulary_leaks(self):
        model_violations = self.inspect_fixture(
            "com/opentypeless/android/config/RuleExplanationModel.java",
            """
            package com.opentypeless.android.config;
            import android.content.Context;
            import java.io.Serializable;
            import java.util.List;
            public class RuleExplanationModel implements Serializable {
                Context context;
                public static RuleExplanationModel from(
                        EffectiveProfileResolver.Request request) {
                    EffectiveProfile profile = EffectiveProfileResolver.resolve(request);
                    if (request.sessionOverrides() != null) return new RuleExplanationModel();
                    return new RuleExplanationModel();
                }
                public List<String> items() { return List.of("route.private"); }
                public String toString() { return "route.private"; }
            }
            """,
        )
        model_rules = {item.rule for item in model_violations}
        self.assertIn("CFG010_MODEL_DEPENDENCY", model_rules)
        self.assertIn("CFG010_MODEL_SHAPE", model_rules)
        self.assertIn("CFG010_DIRECT_PROJECTION", model_rules)
        self.assertIn("CFG010_REDACTION", model_rules)

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/RuleExplanationActivity.java",
            """
            package com.opentypeless.android;
            import com.opentypeless.android.config.EffectiveProfile.ResolvedValue;
            import com.opentypeless.android.config.EffectiveProfile.RuleSource;
            final class RuleExplanationActivity {
                ResolvedValue<String> selected;
                RuleSource recompute() { return RuleSource.APPLICATION; }
            }
            """,
        )
        self.assertIn(
            "CFG010_RESOLVER_VOCABULARY_SCOPE",
            {item.rule for item in outsider_violations},
        )

    def test_cfg011_rejects_open_transactions_unverified_clear_and_secret_identity_drift(self):
        transaction_violations = self.inspect_fixture(
            "com/opentypeless/android/settings/SettingsSaveTransaction.java",
            """
            package com.opentypeless.android.settings;
            public final class SettingsSaveTransaction {
                public interface Steps {
                    void writeSettings();
                    void writeSecrets();
                    void clearJournal();
                }
                public static void execute(Steps steps) {
                    steps.writeSettings();
                    steps.clearJournal();
                    steps.writeSecrets();
                }
            }
            """,
        )
        transaction_rules = {item.rule for item in transaction_violations}
        self.assertIn("CFG011_TRANSACTION_SHAPE", transaction_rules)
        self.assertIn("CFG011_ROLLBACK_PROTOCOL", transaction_rules)

        repository_violations = self.inspect_fixture(
            "com/opentypeless/android/settings/SettingsRepository.java",
            """
            package com.opentypeless.android.settings;
            import com.opentypeless.android.security.SecretStore;
            public final class SettingsRepository {
                public void save(SecretStore store) {
                    store.commitLegacyPrepared(null);
                }
            }
            """,
        )
        repository_rules = {item.rule for item in repository_violations}
        self.assertIn("CFG011_REPOSITORY_PROTOCOL", repository_rules)
        self.assertIn("CFG011_TRANSACTION_REDACTION", repository_rules)

        store_violations = self.inspect_fixture(
            "com/opentypeless/android/security/SecretStore.java",
            """
            package com.opentypeless.android.security;
            import android.content.Context;
            import com.opentypeless.android.config.SecretRef;
            public final class SecretStore {
                public SecretStore(Context context) {}
                public void restoreLegacyPrepared(Object ciphertext) {}
            }
            """,
        )
        self.assertIn(
            "CFG011_SECRET_IDENTITY_ROLLBACK",
            {item.rule for item in store_violations},
        )

        outsider_violations = self.inspect_fixture(
            "com/opentypeless/android/net/SettingsShortcut.java",
            """
            package com.opentypeless.android.net;
            import com.opentypeless.android.settings.SettingsSaveTransaction;
            final class SettingsShortcut {
                SettingsSaveTransaction transaction;
            }
            """,
        )
        self.assertIn(
            "CFG011_TRANSACTION_AUTHORITY",
            {item.rule for item in outsider_violations},
        )

    def inspect_fixture(self, relative_path: str, source: str):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / relative_path
            target.parent.mkdir(parents=True)
            target.write_text(textwrap.dedent(source), encoding="utf-8")
            return inspect_source_tree(root, enforce_legacy_inventory=False)


if __name__ == "__main__":
    unittest.main()
