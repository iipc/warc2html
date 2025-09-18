/*
 * Copyright 2021-2025 National Library of Australia
 * SPDX-License-Identifier: Apache-2.0
 */

package org.netpreserve.warc2html;

import org.netpreserve.jwarc.cdx.CdxFields;
import org.netpreserve.jwarc.cdx.CdxRecord;

import java.time.Instant;

class Resource {
    final String url;
    final Instant instant;
    final int status;
    final String type;
    final String warc;
    final long offset;
    final long length;
    final String locationHeader;
    String path;

    public Resource(String url, Instant instant, int status, String type, String warc, long offset, long length, String locationHeader) {
        this.url = url;
        this.instant = instant;
        this.status = status;
        this.type = type;
        this.warc = warc;
        this.offset = offset;
        this.length = length;
        this.locationHeader = locationHeader;
    }

    public Resource(CdxRecord record) {
        this(record.target(), record.date(), record.status(), record.get(CdxFields.MIME_TYPE),
                record.filename(), record.position(), record.size(), record.redirect());
    }

    public boolean isRedirect() {
        return status >= 300 && status <= 399 && locationHeader != null;
    }
}
