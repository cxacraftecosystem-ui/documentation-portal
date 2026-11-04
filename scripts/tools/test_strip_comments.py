import io
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import strip_comments as sc


def strip(text, lang, path=""):
    return sc.strip_text(text, lang, path)[0]


def run_cli(*argv):
    out = io.StringIO()
    err = io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = sc.main(list(argv))
    return code, out.getvalue(), err.getvalue()


class PythonCases(unittest.TestCase):
    def test_comments_docstrings_and_directives(self):
        src = (
            "#!/usr/bin/env python3\n"
            "# -*- coding: utf-8 -*-\n"
            '"""Module docstring."""\n'
            "\n"
            "import os  # noqa: F401\n"
            "URL = 'http://example.com/#anchor'  # trailing\n"
            'HASH = "# not a comment"\n'
            "\n"
            "\n"
            "def only_doc():\n"
            '    """Only a docstring."""\n'
            "\n"
            "\n"
            "class Thing:\n"
            '    """Class doc.\n'
            "\n"
            "    More.\n"
            '    """\n'
            "\n"
            "    value = 1  # type: int\n"
            "\n"
            "    def method(self):\n"
            "        '''Doc.'''\n"
            "        # a comment line\n"
            '        return f"{self.value} # kept"  # prose  # noqa: E501\n'
        )
        want = (
            "#!/usr/bin/env python3\n"
            "# -*- coding: utf-8 -*-\n"
            "import os  # noqa: F401\n"
            "URL = 'http://example.com/#anchor'\n"
            'HASH = "# not a comment"\n'
            "\n"
            "\n"
            "def only_doc():\n"
            "    pass\n"
            "\n"
            "\n"
            "class Thing:\n"
            "    value = 1  # type: int\n"
            "\n"
            "    def method(self):\n"
            '        return f"{self.value} # kept"  # noqa: E501\n'
        )
        self.assertEqual(strip(src, "python"), want)

    def test_docstring_only_bodies_get_pass(self):
        src = 'class E(Exception):\n    """Doc."""\n\n\nasync def f():\n    """Doc."""\n'
        self.assertEqual(strip(src, "python"), "class E(Exception):\n    pass\n\n\nasync def f():\n    pass\n")

    def test_docstring_followed_by_statement_on_the_same_line(self):
        self.assertEqual(strip('def f(): """d"""; return 1\n', "python"), "def f(): return 1\n")

    def test_files_reading_doc_keep_docstrings(self):
        src = '"""Usage text."""\nimport argparse\n# c\nparser = argparse.ArgumentParser(description=__doc__)\n'
        want = '"""Usage text."""\nimport argparse\nparser = argparse.ArgumentParser(description=__doc__)\n'
        self.assertEqual(strip(src, "python"), want)

    def test_pragmas_kept(self):
        src = "x = 1  # pragma: no cover\ny = 2  # pylint: disable=x\n# fmt: off\nz = 3  # ruff: noqa\n# fmt: on\n"
        self.assertEqual(strip(src, "python"), src)

    def test_crlf_preserved(self):
        self.assertEqual(strip("x = 1  # c\r\n# full\r\ny = 2\r\n", "python"), "x = 1\r\ny = 2\r\n")

    def test_comment_only_file_becomes_empty(self):
        self.assertEqual(strip("# one\n# two\n", "python"), "")


class ScriptCases(unittest.TestCase):
    def test_typescript(self):
        src = (
            '"use client";\n'
            "// leading comment\n"
            'import x from "y"; // trailing\n'
            'const url = "https://example.com//path"; /* block */\n'
            "const re = /\\/\\/[^/]*\\/*/g;\n"
            "const div = a / b / c; // division\n"
            'const tpl = `a ${ `nested ${x /* in expr */}` } // not comment ${"}"} /* nor this */`;\n'
            "// @ts-expect-error -- deliberate\n"
            'const y: number = "s";\n'
            "/* eslint-disable no-console */\n"
            "// eslint-disable-next-line @typescript-eslint/no-explicit-any\n"
            "const z = /*#__PURE__*/ make();\n"
            "/**\n"
            " * JSDoc prose.\n"
            " */\n"
            "export function f() {}\n"
        )
        want = (
            '"use client";\n'
            'import x from "y";\n'
            'const url = "https://example.com//path";\n'
            "const re = /\\/\\/[^/]*\\/*/g;\n"
            "const div = a / b / c;\n"
            'const tpl = `a ${ `nested ${x }` } // not comment ${"}"} /* nor this */`;\n'
            "// @ts-expect-error -- deliberate\n"
            'const y: number = "s";\n'
            "/* eslint-disable no-console */\n"
            "// eslint-disable-next-line @typescript-eslint/no-explicit-any\n"
            "const z = /*#__PURE__*/ make();\n"
            "export function f() {}\n"
        )
        self.assertEqual(strip(src, "ts", "a.ts"), want)

    def test_regex_after_keyword_and_division_after_value(self):
        src = "function f(s) { return /a\\/\\/b/.test(s) ? s.length / 2 : 0; } // end\n"
        self.assertEqual(strip(src, "ts", "a.ts"), "function f(s) { return /a\\/\\/b/.test(s) ? s.length / 2 : 0; }\n")

    def test_regex_with_slash_in_class(self):
        src = "const r = x.replace(/[/*]+/g, ''); // tidy\n"
        self.assertEqual(strip(src, "ts", "a.ts"), "const r = x.replace(/[/*]+/g, '');\n")

    def test_directives_kept(self):
        src = (
            '/// <reference types="next" />\n'
            "// @ts-nocheck\n"
            "// prettier-ignore\n"
            "/* istanbul ignore next */\n"
            "// biome-ignore lint: reason\n"
            "//noinspection JSUnusedGlobalSymbols\n"
            "const a = import(/* webpackChunkName: \"x\" */ \"./x\");\n"
            "// @ts-ignore\n"
            "'use server';\n"
        )
        self.assertEqual(strip(src, "ts", "a.ts"), src)

    def test_tsx_text_strings_and_jsx_comments(self):
        src = (
            "export function C() {\n"
            "  return (\n"
            "    <div title=\"a // b\" data-x={'/* no */'}>\n"
            "      {/* a JSX comment */}\n"
            "      Don't stop // this is text\n"
            "      {value /* inner */}\n"
            '      <a href="https://x.y/#z">https://link</a>\n'
            "      {/* eslint-disable-next-line */}\n"
            "    </div>\n"
            "  );\n"
            "}\n"
            "const g = <T,>(v: T) => v; // generic arrow\n"
        )
        want = (
            "export function C() {\n"
            "  return (\n"
            "    <div title=\"a // b\" data-x={'/* no */'}>\n"
            "      Don't stop // this is text\n"
            "      {value }\n"
            '      <a href="https://x.y/#z">https://link</a>\n'
            "      {/* eslint-disable-next-line */}\n"
            "    </div>\n"
            "  );\n"
            "}\n"
            "const g = <T,>(v: T) => v;\n"
        )
        self.assertEqual(strip(src, "tsx", "a.tsx"), want)

    def test_hashbang_and_js_type_annotations(self):
        src = "#!/usr/bin/env node\n/** @type {import('next').NextConfig} */\nconst c = {}; // note\nexport default c;\n"
        want = "#!/usr/bin/env node\n/** @type {import('next').NextConfig} */\nconst c = {};\nexport default c;\n"
        self.assertEqual(strip(src, "js", "next.config.mjs"), want)

    def test_glued_block_comment_keeps_tokens_apart(self):
        self.assertEqual(strip("let a = b/**/+c;\n", "ts", "a.ts"), "let a = b +c;\n")


class JvmCases(unittest.TestCase):
    def test_kotlin(self):
        src = (
            "#!/usr/bin/env kotlin\n"
            "package a.b // trailing\n"
            "\n"
            "/* outer /* nested */ still comment */\n"
            "/** KDoc */\n"
            '@Suppress("UNCHECKED_CAST")\n'
            "fun f(): String {\n"
            '    val url = "http://x.y/*not*/" // c\n'
            '    val raw = """\n'
            "        // inside raw ${'$'}{x}\n"
            '        /* also inside */ ${ g("/* q */") /* real */ }\n'
            '    """\n'
            "    val ch = '\"' // quote char\n"
            '    val t = "a ${b /* c */} d" //noinspection SpellCheckingInspection\n'
            "    return url\n"
            "}\n"
        )
        want = (
            "#!/usr/bin/env kotlin\n"
            "package a.b\n"
            "\n"
            '@Suppress("UNCHECKED_CAST")\n'
            "fun f(): String {\n"
            '    val url = "http://x.y/*not*/"\n'
            '    val raw = """\n'
            "        // inside raw ${'$'}{x}\n"
            '        /* also inside */ ${ g("/* q */") }\n'
            '    """\n'
            "    val ch = '\"'\n"
            '    val t = "a ${b } d" //noinspection SpellCheckingInspection\n'
            "    return url\n"
            "}\n"
        )
        self.assertEqual(strip(src, "kotlin", "A.kt"), want)

    def test_kotlin_crlf(self):
        self.assertEqual(strip("val a = 1 // c\r\n// d\r\nval b = 2\r\n", "kotlin", "A.kt"), "val a = 1\r\nval b = 2\r\n")

    def test_java_text_block(self):
        src = 'class A { String s = """\n    // text block \\""" still\n    """; /* c */ char q = \'\\\'\'; } // end\n'
        want = 'class A { String s = """\n    // text block \\""" still\n    """; char q = \'\\\'\'; }\n'
        self.assertEqual(strip(src, "java", "A.java"), want)

    def test_gradle_kts(self):
        src = 'plugins {\n    id("com.android.application") // AGP\n}\n/* block */\nval x = "//not"\n'
        self.assertEqual(strip(src, "kotlin", "build.gradle.kts"), 'plugins {\n    id("com.android.application")\n}\nval x = "//not"\n')


class StyleAndDataCases(unittest.TestCase):
    def test_css(self):
        src = (
            "/*! keep license-style */\n"
            "@import url(//fonts.example.com/a.css);\n"
            '.a { background: url(data:image/png;base64,AAA/*x*/) ; content: "/* no */"; } /* trailing */\n'
            "/* stylelint-disable-next-line */\n"
            ".b{color:red}/* glued */.c{}\n"
        )
        want = (
            "/*! keep license-style */\n"
            "@import url(//fonts.example.com/a.css);\n"
            '.a { background: url(data:image/png;base64,AAA/*x*/) ; content: "/* no */"; }\n'
            "/* stylelint-disable-next-line */\n"
            ".b{color:red} .c{}\n"
        )
        self.assertEqual(strip(src, "css", "a.css"), want)

    def test_sql(self):
        src = (
            "-- header\n"
            "SELECT '--not' AS a, E'it\\'s -- no' AS b, \"col--x\" /* c /* nested */ c */ FROM t; -- trailing\n"
            "DO $body$ BEGIN -- inside dollar quote\n"
            "END $body$;\n"
        )
        want = (
            "SELECT '--not' AS a, E'it\\'s -- no' AS b, \"col--x\" FROM t;\n"
            "DO $body$ BEGIN -- inside dollar quote\n"
            "END $body$;\n"
        )
        self.assertEqual(strip(src, "sql", "backend/tests/scale/seed.sql"), want)

    def test_xml(self):
        src = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- top comment -->\n"
            '<resources xmlns:tools="http://schemas.android.com/tools">\n'
            '    <!-- tools:ignore="MissingTranslation" -->\n'
            '    <string name="url">http://x.y/<!-- no --></string>\n'
            '    <string name="cdata"><![CDATA[<!-- kept -->]]></string>\n'
            '    <string name="a" tools:ignore="X">A</string> <!-- trailing -->\n'
            "</resources>\n"
        )
        want = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources xmlns:tools="http://schemas.android.com/tools">\n'
            '    <!-- tools:ignore="MissingTranslation" -->\n'
            '    <string name="url">http://x.y/</string>\n'
            '    <string name="cdata"><![CDATA[<!-- kept -->]]></string>\n'
            '    <string name="a" tools:ignore="X">A</string>\n'
            "</resources>\n"
        )
        self.assertEqual(strip(src, "xml", "res/values/strings.xml"), want)

    def test_toml(self):
        src = (
            "# top\n"
            "[tool.x]  # table comment\n"
            'a = "has # hash"  # trailing\n'
            "b = 'lit # eral'\n"
            'c = """\n'
            "multi # line\n"
            '"""\n'
            "d = '''raw # ''' # after\n"
        )
        want = (
            "[tool.x]\n"
            'a = "has # hash"\n'
            "b = 'lit # eral'\n"
            'c = """\n'
            "multi # line\n"
            '"""\n'
            "d = '''raw # '''\n"
        )
        self.assertEqual(strip(src, "toml", "pyproject.toml"), want)

    def test_properties(self):
        src = "# comment\n! bang comment\nkey=value # not a comment\nmulti=one \\\n  # still value\nother = x\n"
        want = "key=value # not a comment\nmulti=one \\\n  # still value\nother = x\n"
        self.assertEqual(strip(src, "properties", "gradle.properties"), want)

    def test_dotenv(self):
        src = '# Section\nAPI_URL=https://example.com/#x # explain\nQUOTED="value # inside" # after\nEMPTY=\n'
        want = 'API_URL=https://example.com/#x\nQUOTED="value # inside"\nEMPTY=\n'
        self.assertEqual(strip(src, "dotenv", ".env.example"), want)


class ShellAndContainerCases(unittest.TestCase):
    def test_shell(self):
        src = (
            "#!/usr/bin/env bash\n"
            "# shellcheck disable=SC2034\n"
            "set -euo pipefail # trailing\n"
            "echo \"a # b\" 'c # d' $# ${#arr[@]} ${x#prefix} foo#bar\n"
            "url=https://example.com/#frag\n"
            "cat <<'EOF'\n"
            "# heredoc line stays\n"
            "EOF\n"
            "cat <<<\"here # string\"\n"
            "echo one \\\n"
            "  # breaks the continuation\n"
            "echo $(echo inner # sub comment\n"
            ")\n"
        )
        want = (
            "#!/usr/bin/env bash\n"
            "# shellcheck disable=SC2034\n"
            "set -euo pipefail\n"
            "echo \"a # b\" 'c # d' $# ${#arr[@]} ${x#prefix} foo#bar\n"
            "url=https://example.com/#frag\n"
            "cat <<'EOF'\n"
            "# heredoc line stays\n"
            "EOF\n"
            "cat <<<\"here # string\"\n"
            "echo one \\\n"
            "\n"
            "echo $(echo inner\n"
            ")\n"
        )
        self.assertEqual(strip(src, "shell", "deploy.sh"), want)

    def test_a_quoted_heredoc_fed_to_a_shell_is_shell_code(self):
        src = (
            "ssh host 'bash -s' <<'REMOTE'\n"
            "#!/bin/bash\n"
            "# shellcheck disable=SC2086\n"
            "# a remote comment\n"
            "set -e # trailing\n"
            "echo \"# kept\" '# kept too' $# ${#arr[@]}\n"
            "cat <<'CONF' > /etc/x.conf\n"
            "# config data stays\n"
            "CONF\n"
            "python3 - <<'PY'\n"
            "# python stays\n"
            "PY\n"
            "REMOTE\n"
            "ssh host \"SHA='$SHA' bash -s\" <<'R2'\n"
            "  # indented comment\n"
            "echo \"$SHA\"\n"
            "R2\n"
            "sudo bash <<\\R3\n"
            "# escaped marker is quoted too\n"
            "true\n"
            "R3\n"
            "bash -s <<EOF\n"
            "# unquoted heredoc stays, the body is expanded first\n"
            "EOF\n"
            "bash -c 'cat' <<'EOF'\n"
            "# stdin of bash -c is data\n"
            "EOF\n"
            "{ printf 'a=%s\\n' x; cat; } <<'R4' | ssh host 'bash -s'\n"
            "# piped remote comment\n"
            "true\n"
            "R4\n"
            "{\n"
            "  printf 'b\\n'\n"
            "  cat <<'R5'\n"
            "# grouped remote comment\n"
            "true\n"
            "R5\n"
            "} | ssh host 'bash -s'\n"
            "cat <<'R6' | sudo tee /etc/y\n"
            "# tee data stays\n"
            "R6\n"
        )
        want = (
            "ssh host 'bash -s' <<'REMOTE'\n"
            "#!/bin/bash\n"
            "# shellcheck disable=SC2086\n"
            "set -e\n"
            "echo \"# kept\" '# kept too' $# ${#arr[@]}\n"
            "cat <<'CONF' > /etc/x.conf\n"
            "# config data stays\n"
            "CONF\n"
            "python3 - <<'PY'\n"
            "# python stays\n"
            "PY\n"
            "REMOTE\n"
            "ssh host \"SHA='$SHA' bash -s\" <<'R2'\n"
            "echo \"$SHA\"\n"
            "R2\n"
            "sudo bash <<\\R3\n"
            "true\n"
            "R3\n"
            "bash -s <<EOF\n"
            "# unquoted heredoc stays, the body is expanded first\n"
            "EOF\n"
            "bash -c 'cat' <<'EOF'\n"
            "# stdin of bash -c is data\n"
            "EOF\n"
            "{ printf 'a=%s\\n' x; cat; } <<'R4' | ssh host 'bash -s'\n"
            "true\n"
            "R4\n"
            "{\n"
            "  printf 'b\\n'\n"
            "  cat <<'R5'\n"
            "true\n"
            "R5\n"
            "} | ssh host 'bash -s'\n"
            "cat <<'R6' | sudo tee /etc/y\n"
            "# tee data stays\n"
            "R6\n"
        )
        self.assertEqual(strip(src, "shell", "deploy.sh"), want)

    def test_a_workflow_heredoc_fed_to_a_remote_shell_is_stripped(self):
        src = (
            "jobs:\n"
            "  deploy:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - name: Remote\n"
            "        run: |\n"
            "          ssh ubuntu@${{ secrets.HOST }} \"SHA='$SHA' bash -s\" <<'REMOTE'\n"
            "          # 1. THE INTERPRETER\n"
            "          #    the owner's decision\n"
            "          set -euo pipefail # trailing\n"
            "          echo \"# kept\"\n"
            "          sudo tee /etc/app.conf >/dev/null <<'CONF'\n"
            "          # config stays\n"
            "          CONF\n"
            "          REMOTE\n"
        )
        want = (
            "jobs:\n"
            "  deploy:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - name: Remote\n"
            "        run: |\n"
            "          ssh ubuntu@${{ secrets.HOST }} \"SHA='$SHA' bash -s\" <<'REMOTE'\n"
            "          set -euo pipefail\n"
            "          echo \"# kept\"\n"
            "          sudo tee /etc/app.conf >/dev/null <<'CONF'\n"
            "          # config stays\n"
            "          CONF\n"
            "          REMOTE\n"
        )
        self.assertEqual(strip(src, "yaml", ".github/workflows/deploy.yml"), want)
        self.assertEqual(strip(want, "yaml", ".github/workflows/deploy.yml"), want)

    def test_the_workflow_parse_check_reads_shell_heredocs_only(self):
        import verify_strip_workflows as vw

        script = (
            "ssh host 'bash -s' <<'REMOTE'\n"
            "echo remote\n"
            "cat <<'CONF'\n"
            "data\n"
            "CONF\n"
            "REMOTE\n"
            "python3 - <<'PY'\n"
            "print(1)\n"
            "PY\n"
            "{\n"
            "  cat <<'R2'\n"
            "echo grouped\n"
            "R2\n"
            "} | ssh host 'bash -s'\n"
        )
        bodies = list(vw.shell_heredocs(script))
        self.assertEqual(bodies, ["echo remote\ncat <<'CONF'\ndata\nCONF\n", "echo grouped\n"])
        doc = {
            "jobs": {
                "a": {"runs-on": "ubuntu-latest", "steps": [{"run": "echo a"}, {"run": "print(1)", "shell": "python"}]},
                "b": {"runs-on": "windows-latest", "steps": [{"run": "Write-Host b"}, {"run": "echo c", "shell": "bash"}]},
            }
        }
        self.assertEqual(list(vw.shell_runs(doc)), ["echo a", "echo c"])

    def test_dockerfile(self):
        src = (
            "# syntax=docker/dockerfile:1.7\n"
            "# a comment\n"
            "FROM python:3.14-slim AS base\n"
            'RUN echo "# not removed" \\\n'
            "    # comment inside continuation\n"
            "    && echo done\n"
            'ENV NOTE="value # kept" # also kept, part of the instruction\n'
            "RUN <<EOF\n"
            "# heredoc body stays\n"
            "echo hi\n"
            "EOF\n"
        )
        want = (
            "# syntax=docker/dockerfile:1.7\n"
            "FROM python:3.14-slim AS base\n"
            'RUN echo "# not removed" \\\n'
            "    && echo done\n"
            'ENV NOTE="value # kept" # also kept, part of the instruction\n'
            "RUN <<EOF\n"
            "# heredoc body stays\n"
            "echo hi\n"
            "EOF\n"
        )
        self.assertEqual(strip(src, "dockerfile", "backend/Dockerfile"), want)

    def test_workflow_yaml(self):
        src = (
            "# top comment\n"
            "name: CI  # trailing\n"
            "on:\n"
            "  push:\n"
            "    branches: [\"main#1\", 'it''s # fine']\n"
            "jobs:\n"
            "  build:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v4.2.0\n"
            "      - name: Shell\n"
            "        run: |\n"
            "          # shell comment\n"
            "          echo \"# kept\" # trailing shell comment\n"
            "          cat <<EOF\n"
            "          # heredoc stays\n"
            "          EOF\n"
            "      - name: Python\n"
            "        shell: python\n"
            "        run: |\n"
            "          # python comment stays as data\n"
            "          print(\"x\")\n"
            "      - name: Text\n"
            "        env:\n"
            "          NOTE: |\n"
            "            # not a comment, block scalar text\n"
            "        run: echo hi # yaml comment\n"
        )
        want = (
            "name: CI\n"
            "on:\n"
            "  push:\n"
            "    branches: [\"main#1\", 'it''s # fine']\n"
            "jobs:\n"
            "  build:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            "      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v4.2.0\n"
            "      - name: Shell\n"
            "        run: |\n"
            "          echo \"# kept\"\n"
            "          cat <<EOF\n"
            "          # heredoc stays\n"
            "          EOF\n"
            "      - name: Python\n"
            "        shell: python\n"
            "        run: |\n"
            "          # python comment stays as data\n"
            "          print(\"x\")\n"
            "      - name: Text\n"
            "        env:\n"
            "          NOTE: |\n"
            "            # not a comment, block scalar text\n"
            "        run: echo hi\n"
        )
        self.assertEqual(strip(src, "yaml", ".github/workflows/ci.yml"), want)

    def test_plain_yaml_block_scalars_are_data(self):
        src = "a: |\n  # data\n  x\nb: 'q # q' # c\nc: plain#notcomment\n"
        self.assertEqual(strip(src, "yaml", "infra/k8s/x.yaml"), "a: |\n  # data\n  x\nb: 'q # q'\nc: plain#notcomment\n")


class ScopeCases(unittest.TestCase):
    def test_exclusions(self):
        excluded = [
            "backend/prisma/migrations/20260101000000_x/migration.sql",
            "backend/prisma/migrations/migration_lock.toml",
            "backend/prisma/deferred/20261120090000_x/migration.sql",
            "frontend/src/generated/client.ts",
            "frontend/lib/generated/record-completeness.json",
            "frontend/package-lock.json",
            "backend/requirements.lock",
            "android/gradlew",
            "android/gradle/wrapper/gradle-wrapper.properties",
            "frontend/public/vendor.min.js",
            "frontend/node_modules/x/index.js",
            "backend/.venv/lib/x.py",
            "README.md",
            "shared/enums.json",
            "backend/tests/fixtures/transcript/scribe_v2.json",
            "frontend/e2e/fixtures/trace-parity/MANIFEST.txt",
            "android/app/src/test/resources/dw.json",
            "android/app/src/main/assets/model-viewer/viewer.js",
            "frontend/next-env.d.ts",
            ".env",
            "infra/terraform/main.tf",
            ".claude/skills/security-audit/validate-findings.cjs",
            ".claude/skills/security-audit/validate-coverage-ledger.test.cjs",
        ]
        for rel in excluded:
            self.assertIsNotNone(sc.path_exclusion(rel), rel)
        included = {
            "backend/app/main.py": "python",
            "frontend/app/page.tsx": "tsx",
            "frontend/lib/x.ts": "ts",
            "frontend/e2e/fixtures/location.ts": "ts",
            "frontend/eslint.config.mjs": "js",
            "frontend/postcss.config.js": "jsx",
            "android/app/src/main/java/A.kt": "kotlin",
            "android/build.gradle.kts": "kotlin",
            "frontend/app/globals.css": "css",
            "backend/deploy/blue_green.sh": "shell",
            ".github/workflows/checks.yml": "yaml",
            "backend/pyproject.toml": "toml",
            "android/gradle.properties": "properties",
            "frontend/.env.local.example": "dotenv",
            ".env.example": "dotenv",
            "backend/Dockerfile": "dockerfile",
            "backend/tests/scale/seed_bench.sql": "sql",
            "android/app/src/main/res/values/strings.xml": "xml",
            "frontend/components/map/coverage/Map.tsx": "tsx",
        }
        for rel, lang in included.items():
            self.assertIsNone(sc.path_exclusion(rel), rel)
            self.assertEqual(sc.language_of(rel), lang, rel)

    def test_generated_and_minified_content(self):
        self.assertEqual(sc.content_exclusion("// GENERATED by scripts/x.py from shared/. Do not edit.\nx\n"), "generated code")
        self.assertEqual(sc.content_exclusion("x = 1\n" + "y" * 3000 + "\n"), "minified or generated data")
        self.assertIsNone(sc.content_exclusion('"""Markdown generated by OUR code."""\n'))


class CliCases(unittest.TestCase):
    def test_check_write_and_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "pkg").mkdir()
            (root / "pkg" / "a.py").write_bytes(b"x = 1  # c\r\n")
            (root / "pkg" / "b.ts").write_text("// @ts-nocheck\nexport const b = 1;\n", encoding="utf-8")
            (root / "pkg" / "c.md").write_text("# heading\n", encoding="utf-8")
            code, out, _ = run_cli("--check", "--root", tmp)
            self.assertEqual(code, 1)
            self.assertIn("pkg/a.py:1:8: comment: # c", out)
            self.assertNotIn("b.ts", out)
            self.assertNotIn("c.md", out)
            dest = root / "copies"
            code, _, _ = run_cli("--out", str(dest), "--root", tmp, str(root / "pkg"))
            self.assertEqual(code, 0)
            self.assertEqual((dest / "pkg" / "a.py").read_bytes(), b"x = 1\r\n")
            self.assertEqual((root / "pkg" / "a.py").read_bytes(), b"x = 1  # c\r\n")
            code, _, _ = run_cli("--write", "--root", tmp, str(root / "pkg"))
            self.assertEqual(code, 0)
            self.assertEqual((root / "pkg" / "a.py").read_bytes(), b"x = 1\r\n")
            code, _, _ = run_cli("--check", "--root", tmp, str(root / "pkg"))
            self.assertEqual(code, 0)

    def test_lex_errors_are_reported_not_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "bad.py").write_text("def f(:\n    pass  # c\n", encoding="utf-8")
            code, _, err = run_cli("--write", "--root", tmp)
            self.assertEqual(code, 2)
            self.assertIn("bad.py: error", err)
            self.assertEqual((Path(tmp) / "bad.py").read_text(encoding="utf-8"), "def f(:\n    pass  # c\n")


class RepositorySweep(unittest.TestCase):
    def test_every_file_in_scope_strips_safely(self):
        root = sc.ROOT
        failures = []
        scanned = 0
        edited = 0
        for rel in sc.candidates(root, []):
            lang, text, reason = sc.in_scope(root, rel)
            if reason:
                continue
            scanned += 1
            try:
                new, edits = sc.strip_text(text, lang, rel)
                again, more = sc.strip_text(new, lang, rel)
            except (sc.StripError, RecursionError) as exc:
                failures.append(f"{rel}: {exc!r}")
                continue
            if more or again != new:
                failures.append(f"{rel}: not idempotent")
            if edits:
                edited += 1
        print(f"\n{scanned} file(s) in scope, {edited} with comments to strip", file=sys.stderr)
        self.assertGreater(scanned, 100)
        self.assertEqual(failures, [], "\n".join(failures))


if __name__ == "__main__":
    unittest.main()
