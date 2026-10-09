import { join } from "node:path";

import { expect, test } from "@playwright/test";
import { ESLint } from "eslint";

/**
 * THE LINT CONFIG'S RULES RUN — NOT JUST LOAD.
 *
 * `npm run lint` exiting 0 says two different things depending on the day: "nothing in the tree breaks
 * a rule", or "the rules are not running". The second is not hypothetical here. ESLint 10 removed the
 * rule-context methods eslint-plugin-react 7.37.5 and eslint-plugin-import 2.32.0 still call
 * (`context.getFilename()`, `getSourceCode()`, `parserOptions`); under eslint-config-next 16.3 that
 * crashed every lint (vercel/next.js#89764), and 16.4.0 survives it only through its own
 * `fixupPluginRules` shim (eslint-config-next/dist/rule-context.js). eslint-plugin-jsx-a11y 6.10.2 is
 * NOT shimmed. A shim that stopped wrapping, or a plugin release that degraded a rule to a no-op,
 * would turn the lint step green and empty at the same time, and nothing else in CI would notice.
 *
 * So this lints a source string full of deliberate violations, through the repository's own
 * `eslint.config.mjs`, as if it were a file under components/, and requires every one of the rules
 * below to fire. The list is one rule per violation in PROBE and covers every plugin the config
 * loads: eslint-plugin-react (both shimmed and context-free rules), react-hooks (the classic two and
 * a React Compiler rule), all six jsx-a11y rules eslint-config-next enables, @next/next, and import.
 * Measured on 2026-10-09: ESLint 9.39.5 and 10.12.0 report exactly these 25 messages, at the same
 * lines and columns.
 *
 * IF THIS FAILS after a dependency bump, a rule that used to run no longer does: read which one, and
 * do not "fix" it by deleting the name. If it fails because the config deliberately dropped a rule or
 * a plugin, delete that rule's violation from PROBE and its name from EXPECTED in the same change.
 */

const FRONTEND = join(__dirname, "..");

const PROBE = `import React, { memo, useEffect, useRef, useState } from "react";
import ReactDOM from "react-dom";

export function ProbeList({ items, show }: { items: string[]; show: boolean }) {
  if (show) {
    const [value] = useState(0);
    void value;
  }
  const ref = useRef(0);
  const [count] = useState(1);
  useEffect(() => {
    console.log(count);
  }, []);
  return (
    <div>
      {items.map((item) => <span>{item}</span>)}
      <img src="/x.png" />
      <p>Don't stop</p>
      <div id="a" id="b" />
      <div children="x" />
      <div dangerouslySetInnerHTML={{ __html: "x" }}>child</div>
      <div>// not a comment</div>
      <NotDefinedAnywhere />
      <div aria-labeledby="x" />
      <div aria-hidden="yes" />
      <div role="checkbox" />
      <meta aria-hidden="true" />
      <ul><li aria-checked="true">x</li></ul>
      <script src="https://example.com/a.js" />
      <span>{ref.current}</span>
    </div>
  );
}

export class ProbeClass extends React.Component<object, { a: number }> {
  componentDidMount() {
    this.state.a = 2;
    if (this.isMounted()) ReactDOM.findDOMNode(this);
  }
  render() {
  }
}

export const ProbeMemo = memo(function () { return <div ref="legacy" />; });

export default { probe: true };
`;

const EXPECTED = [
  "@next/next/no-img-element",
  "@next/next/no-sync-scripts",
  "import/no-anonymous-default-export",
  "jsx-a11y/alt-text",
  "jsx-a11y/aria-props",
  "jsx-a11y/aria-proptypes",
  "jsx-a11y/aria-unsupported-elements",
  "jsx-a11y/role-has-required-aria-props",
  "jsx-a11y/role-supports-aria-props",
  "react-hooks/exhaustive-deps",
  "react-hooks/refs",
  "react-hooks/rules-of-hooks",
  "react/display-name",
  "react/jsx-key",
  "react/jsx-no-comment-textnodes",
  "react/jsx-no-duplicate-props",
  "react/jsx-no-undef",
  "react/no-children-prop",
  "react/no-danger-with-children",
  "react/no-direct-mutation-state",
  "react/no-find-dom-node",
  "react/no-is-mounted",
  "react/no-string-refs",
  "react/no-unescaped-entities",
  "react/require-render-return"
];

test("every plugin the lint config loads reports its probe violation", async () => {
  // Loading every plugin and linting one file takes a few seconds; the default 90 s is plenty.
  const eslint = new ESLint({ cwd: FRONTEND });
  const [result] = await eslint.lintText(PROBE, { filePath: join(FRONTEND, "components", "__eslint_probe__.tsx") });

  expect(result.fatalErrorCount, "the probe must parse; a fatal here means the parser, not a rule").toBe(0);
  const fired = [...new Set(result.messages.map((message) => message.ruleId ?? "(no rule)"))].sort();
  expect(fired).toEqual(EXPECTED);
});
