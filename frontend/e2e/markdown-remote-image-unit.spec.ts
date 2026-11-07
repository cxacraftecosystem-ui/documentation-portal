import { expect, test } from "@playwright/test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { Markdown } from "@/components/Markdown";

const render = (text: string) => renderToStaticMarkup(createElement(Markdown, { text }));

test.describe("Stored Markdown never loads a remote image by itself", () => {
  test("an image in stored text becomes a link the viewer chooses to open", () => {
    const html = render("Before ![tracking pixel](http://127.0.0.1:9/p.png) after");
    expect(html).not.toContain("<img");
    expect(html).toContain('href="http://127.0.0.1:9/p.png"');
    expect(html).toContain("tracking pixel");
  });

  test("an https image and a reference-style image are not fetched either", () => {
    expect(render("![x](https://evil.invalid/a.gif)")).not.toContain("<img");
    expect(render("![y][ref]\n\n[ref]: https://evil.invalid/b.gif")).not.toContain("<img");
  });

  test("an image with no safe address shows only its words", () => {
    const html = render("![label](javascript:alert(1))");
    expect(html).not.toContain("<img");
    expect(html).not.toContain("javascript:");
    expect(html).toContain("label");
  });

  test("ordinary Markdown still renders", () => {
    const html = render("**bold** and [a link](https://example.invalid)");
    expect(html).toContain("<strong");
    expect(html).toContain('href="https://example.invalid"');
  });
});
