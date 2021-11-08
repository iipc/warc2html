/*
 * Copyright 2021 National Library of Australia
 * SPDX-License-Identifier: Apache-2.0
 */

package org.netpreserve.warc2html;

import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;


import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class PathUtils {
    private static final Pattern BAD_FILENAME_PATTERN = Pattern.compile("[\\x00-\\x1f<>:\"/\\\\|?*]");
    private static final Pattern WINDOWS_RESERVED_NAMES = Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?=$|\\.)", CASE_INSENSITIVE);

    public static String replaceBadFilenameChars(String filename) {
        filename = BAD_FILENAME_PATTERN.matcher(filename).replaceAll("_");
        filename = WINDOWS_RESERVED_NAMES.matcher(filename).replaceAll("$1_");
        if (filename.endsWith(".")) filename += "_";
        return filename;
    }

    public static String[] splitExtension(String filename) {
        int slashOffset = filename.lastIndexOf('/');
        int dotOffset = filename.lastIndexOf('.');
        if (dotOffset >= 0 && dotOffset > slashOffset) {
            return new String[]{filename.substring(0, dotOffset), filename.substring(dotOffset)};
        } else {
            return new String[]{filename, ""};
        }
    }

    public static String pathFromUrl(String url, String forcedExtension) {
        ParsedUrl parsedUrl = ParsedUrl.parseUrl(url);
        Canonicalizer.WHATWG.canonicalize(parsedUrl);
        StringBuilder builder = new StringBuilder();
        builder.append(parsedUrl.getHost());
        if (!parsedUrl.getColonBeforePort().isEmpty()) {
            builder.append(";");
            builder.append(parsedUrl.getPort());
        }
        builder.append("/");
        String[] segments = parsedUrl.getPath().split("/", -1);
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].isEmpty()) continue;
            builder.append(replaceBadFilenameChars(segments[i]));
            builder.append("/");
        }

        String filename = replaceBadFilenameChars(segments[segments.length - 1]);
        if (filename.isEmpty()) filename = "index.html";
        String[] basenameAndExtension = splitExtension(filename);
        String basename = basenameAndExtension[0];
        String extension = basenameAndExtension[1];
        if (forcedExtension != null) {
            extension = "." + forcedExtension;
        }

        builder.append(basename);
        if (!parsedUrl.getQuestionMark().isEmpty()) {
            builder.append(";");
            builder.append(replaceBadFilenameChars(parsedUrl.getQuery()));
        }
        builder.append(extension);

        return builder.toString();
    }

    public static String relativize(String path, String basePath) {
        StringBuilder builder = new StringBuilder();
        String[] segments = path.split("/", -1);
        String[] baseSegments = basePath.split("/", -1);

        int i;

        // skip over all common prefix segments
        for (i = 0; i < segments.length && segments[i].equals(baseSegments[i]); i++) {
            // no action
        }

        // add ../ for every directory segment remaining in the base path
        for (int j = i; j < baseSegments.length - 1; j++) {
            builder.append("../");
        }

        // add the portion of the original path after the common prefix
        for (; i < segments.length; i++) {
            builder.append(segments[i]);
            if (i < segments.length - 1) {
                builder.append("/");
            }
        }
        return builder.toString();
    }
}
