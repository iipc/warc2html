/*
 * Copyright 2021 National Library of Australia
 * SPDX-License-Identifier: Apache-2.0
 */

package org.netpreserve.warc2html;

import org.junit.Test;

import static org.junit.Assert.*;

public class Warc2HtmlTest {
    @Test
    public void sanitizeFilename() {
        assertEquals("hello.html_foo=1&bar=__baz", PathUtils.replaceBadFilenameChars("hello.html?foo=1&bar=<>baz"));
        assertEquals("nUl_.txt", PathUtils.replaceBadFilenameChars("nUl.txt"));
        assertEquals("._", PathUtils.replaceBadFilenameChars("."));
        assertEquals("foo._", PathUtils.replaceBadFilenameChars("foo."));
    }

    @Test
    public void makePath() {
        assertEquals("example.org/foo/bar;x=1&y=2.html", PathUtils.pathFromUrl("http://example.org/foo/bar.html?x=1&y=2", null));
        assertEquals("example.org/foo/bar;x=1&y=2.txt", PathUtils.pathFromUrl("http://example.org/foo/bar.html?x=1&y=2", "txt"));
    }

    @Test
    public void relativizePath() {
        assertEquals("a", PathUtils.relativize("a", "b"));
        assertEquals("../a", PathUtils.relativize("a", "b/"));
        assertEquals("../a", PathUtils.relativize("a", "b/x"));
        assertEquals("c/d.html", PathUtils.relativize("a/b/c/d.html", "a/b/e.html"));
        assertEquals("../e.html", PathUtils.relativize("a/b/e.html", "a/b/c/d.html"));
        assertEquals("../../z/e.html", PathUtils.relativize("a/b/z/e.html", "a/b/c/d/e.html"));
    }
}