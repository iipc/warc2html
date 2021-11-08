/*
 * Copyright 2021 National Library of Australia
 * SPDX-License-Identifier: Apache-2.0
 */

package org.netpreserve.warc2html;

import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRecord;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.ZoneOffset.UTC;

public class Warc2Html {
    private static final DateTimeFormatter ARC_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US).withZone(UTC);
    private static final Map<String, String> DEFAULT_FORCED_EXTENSIONS = loadForcedExtensions();
    private final Map<String, Resource> resourcesByUrlKey = new HashMap<>();
    private final Map<String, Resource> resourcesByPath = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> forcedExtensions = new HashMap<>(DEFAULT_FORCED_EXTENSIONS);
    private String warcBaseLocation = "";

    public static void main(String[] args) throws IOException {
        Warc2Html warc2Html = new Warc2Html();
        Path outputDir = Paths.get(".");

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("-")) {
                switch (args[i]) {
                    case "-h":
                    case "--help":
                        System.out.println("Usage: warc2html [-o outdir] file1.warc [file2.warc ...]");
                        System.out.println("       warc2html [-o outdir] -b http://example.org/warcs/ file1.cdx [file2.cdx ...]");
                        return;
                    case "-b":
                    case "--warc-base":
                        warc2Html.setWarcBaseLocation(args[++i]);
                        break;
                    case "-o":
                    case "--output-dir":
                        outputDir = Paths.get(args[++i]);
                        break;
                    default:
                        System.err.println("warc2html: unknown option: " + args[i]);
                        System.exit(1);
                        return;
                }
            } else {
                try (InputStream stream = new FileInputStream(args[i])) {
                    warc2Html.load(args[i], stream);
                }
            }
        }

        warc2Html.resolveRedirects();
        warc2Html.writeTo(outputDir);
    }

    public static String makeUrlKey(String url) {
        ParsedUrl parsedUrl = ParsedUrl.parseUrl(url);
        Canonicalizer.AGGRESSIVE.canonicalize(parsedUrl);
        return parsedUrl.toString();
    }

    private static String ensureUniquePath(Map<String, Resource> pathIndex, String path) {
        if (pathIndex.containsKey(path)) {
            String[] basenameAndExtension = PathUtils.splitExtension(path);
            for (long i = 1; pathIndex.containsKey(path); i++) {
                path = basenameAndExtension[0] + "~" + i + basenameAndExtension[1];
            }
        }
        return path;
    }

    private static Map<String, String> loadForcedExtensions() {
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(Warc2Html.class.getResourceAsStream("forced.extensions"), "forced.extensions resource missing")))) {
            var map = new HashMap<String, String>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank()) continue;
                String[] fields = line.strip().split("\\s+");
                map.put(fields[0], fields[1]);
            }
            return Collections.unmodifiableMap(map);
        } catch (IOException e) {
            throw new RuntimeException("Error loading forced.extensions", e);
        }
    }

    private void load(String filename, InputStream stream) throws IOException {
        if (!stream.markSupported()) stream = new BufferedInputStream(stream);
        stream.mark(1);
        int firstByte = stream.read();
        stream.reset();
        if (firstByte == 'W' || firstByte == 0x1f || firstByte == 'f') {
            loadWarc(filename, stream);
        } else {
            loadCdx(new BufferedReader(new InputStreamReader(stream, UTF_8)));
        }
    }

    public void loadCdx(BufferedReader reader) throws IOException {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            if (line.isBlank() || line.startsWith(" ")) continue;

            String[] fields = line.split(" ");
            Instant instant = ARC_DATE_FORMAT.parse(fields[1], Instant::from);
            String url = fields[2];
            String type = fields[3];
            int status = fields[4].equals("-") ? 0 : Integer.parseInt(fields[4]);
            long length = Long.parseLong(fields[8]);
            long offset = Long.parseLong(fields[9]);
            String warc = fields[11];
            String locationHeader = fields[6];

            add(new Resource(url, instant, status, type, warc, offset, length, locationHeader));
        }
    }

    private void loadWarc(String filename, InputStream stream) throws IOException {
        WarcReader reader = new WarcReader(stream);
        WarcRecord record = reader.next().orElse(null);
        while (record != null) {
            if (!(record instanceof WarcResponse)) {
                record = reader.next().orElse(null);
                continue;
            }
            WarcResponse response = (WarcResponse) record;
            String url = response.target();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                record = reader.next().orElse(null);
                continue;
            }
            Instant instant = response.date();
            String type;
            try {
                type = response.payloadType().base().toString();
            } catch (IllegalArgumentException e) {
                type = "application/octet-stream";
            }
            int status = response.http().status();
            long offset = reader.position();
            String locationHeader = response.http().headers().first("Location").orElse(null);

            record = reader.next().orElse(null);
            long length = reader.position() - offset;

            add(new Resource(url, instant, status, type, filename, offset, length, locationHeader));
        }
    }

    private void add(Resource resource) {
        if (resource.status >= 400) return;

        String path = PathUtils.pathFromUrl(resource.url, forcedExtensions.get(resource.type));

        path = ensureUniquePath(resourcesByPath, path);

        resource.path = path;
        resourcesByPath.put(path, resource);

        String urlKey = makeUrlKey(resource.url);

        Resource existing = resourcesByUrlKey.get(urlKey);
        boolean keepExisting;

        if (existing == null) {
            keepExisting = false;
        } else if (existing.isRedirect() && !resource.isRedirect()) {
            keepExisting = false;
        } else if (resource.isRedirect() && !existing.isRedirect()) {
            keepExisting = true;
        } else if (resource.instant.isBefore(existing.instant)) {
            keepExisting = true;
        } else {
            keepExisting = false;
        }

        if (!keepExisting) {
            resourcesByUrlKey.put(urlKey, resource);
        }
    }

    protected WarcReader openWarc(String filename, long offset, long length) throws IOException {
        String pathOrUrl = warcBaseLocation + filename;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            var connection = (HttpURLConnection) new URL(pathOrUrl).openConnection();
            if (length > 0) {
                connection.addRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
            } else if (offset > 0) {
                connection.addRequestProperty("Range", "bytes=" + offset + "-");
            }
            return new WarcReader(connection.getInputStream());
        } else {
            FileChannel channel = FileChannel.open(Paths.get(pathOrUrl));
            channel.position(offset);
            return new WarcReader(channel);
        }
    }

    public void writeTo(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        try (var filelist = Files.newBufferedWriter(outDir.resolve("filelist.txt"))) {
            for (Resource resource : resourcesByPath.values()) {
                try (WarcReader reader = openWarc(resource.warc, resource.offset, resource.length)) {
                    WarcRecord record = reader.next().orElseThrow();
                    if (!(record instanceof WarcResponse)) throw new IllegalStateException();
                    WarcResponse response = (WarcResponse) record;

                    Path path = outDir.resolve(resource.path);
                    Files.createDirectories(path.getParent());

                    long linksRewritten = 0;
                    try (OutputStream output = Files.newOutputStream(path)) {
                        InputStream input = response.http().body().stream();
                        if (resource.isRedirect()) {
                            String destination = rewriteLink(resource.locationHeader, URI.create(resource.url), resource.path);
                            if (destination == null) destination = resource.locationHeader;
                            output.write(("<meta http-equiv=\"refresh\" content=\"0; url=" + destination + "\">\n").getBytes(UTF_8));
                        } else if (resource.type.equals("text/html")) {
                            URI baseUri = URI.create(resource.url);
                            linksRewritten = LinkRewriter.rewriteHTML(input, output, url -> rewriteLink(url, baseUri, resource.path));
                        } else {
                            input.transferTo(output);
                        }
                    }

                    System.out.println(resource.path + " " + resource.url + " " + resource.type + " " + linksRewritten);
                    filelist.write(resource.path + " " + ARC_DATE_FORMAT.format(resource.instant) + " " + resource.url +
                            " " + resource.type + " " + resource.status + " " +
                            (resource.locationHeader == null ? "-" : resource.locationHeader) + "\r\n");
                }
            }
        }
    }


    private String rewriteLink(String url, URI baseUri, String basePath) {

        URI uri;
        try {
            uri = baseUri.resolve(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Resource resource = resourcesByUrlKey.get(makeUrlKey(uri.toString()));
        if (resource == null) return null;
        return PathUtils.relativize(resource.path, basePath);
    }

    public void setWarcBaseLocation(String warcBaseLocation) {
        this.warcBaseLocation = warcBaseLocation;
    }

    public void resolveRedirects() {
        this.resourcesByUrlKey.replaceAll((key, resource) -> {
            if (resource.isRedirect()) {
                return resourcesByUrlKey.getOrDefault(makeUrlKey(resource.locationHeader), resource);
            } else {
                return resource;
            }
        });
    }
}
