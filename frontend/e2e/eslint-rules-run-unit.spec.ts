import { join } from "node:path";

import { expect, test } from "@playwright/test";
import { ESLint } from "eslint";

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
  const eslint = new ESLint({ cwd: FRONTEND });
  const [result] = await eslint.lintText(PROBE, { filePath: join(FRONTEND, "components", "__eslint_probe__.tsx") });

  expect(result.fatalErrorCount, "the probe must parse; a fatal here means the parser, not a rule").toBe(0);
  const fired = [...new Set(result.messages.map((message) => message.ruleId ?? "(no rule)"))].sort();
  const silent = EXPECTED.filter((rule) => !fired.includes(rule));
  expect(silent, `rules that no longer report their probe violation (fired: ${fired.join(", ")})`).toEqual([]);
});
