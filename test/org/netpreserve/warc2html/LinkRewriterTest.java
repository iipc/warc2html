/*
 * Copyright 2021 National Library of Australia
 * SPDX-License-Identifier: Apache-2.0
 */

package org.netpreserve.warc2html;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static org.junit.Assert.*;

public class LinkRewriterTest {
    @Test
    public void testRewrite() throws IOException {
        assertEquals("<a href=\"HELLO.HTML\" class=fancy>link</a>" +
                        "<img src=\"//IMAGES.EXAMPLE.ORG/CAT.JPG\">",
                rewrite("<a href=hello.html class=fancy>link</a>" +
                        "<img src=//images.example.org/cat.jpg>", String::toUpperCase));
    }
    @Test
    public void testRewriteCSS() {
        assertEquals("body { background: url(TEST.JPG); } ", LinkRewriter.rewriteCSS("body { background: url('test.jpg' ); } ", String::toUpperCase));
    }

    public String rewrite(String html, Function<String, String> mapping) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkRewriter.rewriteHTML(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), output,
                mapping);
        return output.toString(StandardCharsets.UTF_8);
    }
}