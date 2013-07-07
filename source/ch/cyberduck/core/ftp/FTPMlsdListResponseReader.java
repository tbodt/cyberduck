package ch.cyberduck.core.ftp;

/*
 * Copyright (c) 2002-2013 David Kocher. All rights reserved.
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to feedback@cyberduck.ch
 */

import ch.cyberduck.core.AttributedList;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.Permission;
import ch.cyberduck.core.date.MDTMMillisecondsDateFormatter;
import ch.cyberduck.core.date.MDTMSecondsDateFormatter;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.net.ftp.FTPFileEntryParser;
import org.apache.log4j.Logger;

import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @version $Id$
 */
public class FTPMlsdListResponseReader implements FTPResponseReader {
    private static final Logger log = Logger.getLogger(FTPMlsdListResponseReader.class);

    @Override
    public boolean read(final AttributedList<Path> children, final FTPSession session, final Path parent,
                        final FTPFileEntryParser parser, final List<String> replies) {
        if(null == replies) {
            // This is an empty directory
            return false;
        }
        boolean success = false; // At least one entry successfully parsed
        for(String line : replies) {
            final Map<String, Map<String, String>> file = this.parseFacts(line);
            if(null == file) {
                log.error(String.format("Error parsing line %s", line));
                continue;
            }
            for(String name : file.keySet()) {
                final Path parsed = new Path(parent, Path.getName(name), Path.FILE_TYPE);
                // size       -- Size in octets
                // modify     -- Last modification time
                // create     -- Creation time
                // type       -- Entry type
                // unique     -- Unique id of file/directory
                // perm       -- File permissions, whether read, write, execute is allowed for the login id.
                // lang       -- Language of the file name per IANA [11] registry.
                // media-type -- MIME media-type of file contents per IANA registry.
                // charset    -- Character set per IANA registry (if not UTF-8)
                for(Map<String, String> facts : file.values()) {
                    if(!facts.containsKey("type")) {
                        log.error(String.format("No type fact in line %s", line));
                        continue;
                    }
                    if("dir".equals(facts.get("type").toLowerCase(java.util.Locale.ENGLISH))) {
                        parsed.attributes().setType(Path.DIRECTORY_TYPE);
                    }
                    else if("file".equals(facts.get("type").toLowerCase(java.util.Locale.ENGLISH))) {
                        parsed.attributes().setType(Path.FILE_TYPE);
                    }
                    else {
                        log.warn("Ignored type: " + line);
                        break;
                    }
                    if(name.contains(String.valueOf(Path.DELIMITER))) {
                        if(!name.startsWith(parent.getAbsolute() + Path.DELIMITER)) {
                            // Workaround for #2434.
                            log.warn("Skip listing entry with delimiter:" + name);
                            continue;
                        }
                    }
                    if(!success) {
                        if("dir".equals(facts.get("type").toLowerCase(java.util.Locale.ENGLISH)) && parent.getName().equals(name)) {
                            log.warn("Possibly bogus response:" + line);
                        }
                        else {
                            success = true;
                        }
                    }
                    if(facts.containsKey("size")) {
                        parsed.attributes().setSize(Long.parseLong(facts.get("size")));
                    }
                    if(facts.containsKey("unix.uid")) {
                        parsed.attributes().setOwner(facts.get("unix.uid"));
                    }
                    if(facts.containsKey("unix.owner")) {
                        parsed.attributes().setOwner(facts.get("unix.owner"));
                    }
                    if(facts.containsKey("unix.gid")) {
                        parsed.attributes().setGroup(facts.get("unix.gid"));
                    }
                    if(facts.containsKey("unix.group")) {
                        parsed.attributes().setGroup(facts.get("unix.group"));
                    }
                    if(facts.containsKey("unix.mode")) {
                        try {
                            parsed.attributes().setPermission(new Permission(Integer.parseInt(facts.get("unix.mode"))));
                        }
                        catch(NumberFormatException e) {
                            log.error(String.format("Failed to parse fact %s", facts.get("unix.mode")));
                        }
                    }
                    if(facts.containsKey("modify")) {
                        parsed.attributes().setModificationDate(this.parseTimestamp(facts.get("modify")));
                    }
                    if(facts.containsKey("create")) {
                        parsed.attributes().setCreationDate(this.parseTimestamp(facts.get("create")));
                    }
                    if(facts.containsKey("charset")) {
                        if(!facts.get("charset").equalsIgnoreCase(session.getEncoding())) {
                            log.error(String.format("Incompatible charset %s but session is configured with %s",
                                    facts.get("charset"), session.getEncoding()));
                        }
                    }
                    children.add(parsed);
                }
            }
        }
        return success;
    }

    /**
     * Parse the timestamp using the MTDM format
     *
     * @param timestamp Date string
     * @return Milliseconds
     */
    protected long parseTimestamp(final String timestamp) {
        if(null == timestamp) {
            return -1;
        }
        try {
            Date parsed = new MDTMSecondsDateFormatter().parse(timestamp);
            return parsed.getTime();
        }
        catch(ParseException e) {
            log.warn("Failed to parse timestamp:" + e.getMessage());
            try {
                Date parsed = new MDTMMillisecondsDateFormatter().parse(timestamp);
                return parsed.getTime();
            }
            catch(ParseException f) {
                log.warn("Failed to parse timestamp:" + f.getMessage());
            }
        }
        log.error(String.format("Failed to parse timestamp %s", timestamp));
        return -1;
    }

    /**
     * The "facts" for a file in a reply to a MLSx command consist of
     * information about that file.  The facts are a series of keyword=value
     * pairs each followed by semi-colon (";") characters.  An individual
     * fact may not contain a semi-colon in its name or value.  The complete
     * series of facts may not contain the space character.  See the
     * definition or "RCHAR" in section 2.1 for a list of the characters
     * that can occur in a fact value.  Not all are applicable to all facts.
     * <p/>
     * A sample of a typical series of facts would be: (spread over two
     * lines for presentation here only)
     * <p/>
     * size=4161;lang=en-US;modify=19970214165800;create=19961001124534;
     * type=file;x.myfact=foo,bar;
     * <p/>
     * This document defines a standard set of facts as follows:
     * <p/>
     * size       -- Size in octets
     * modify     -- Last modification time
     * create     -- Creation time
     * type       -- Entry type
     * unique     -- Unique id of file/directory
     * perm       -- File permissions, whether read, write, execute is
     * allowed for the login id.
     * lang       -- Language of the file name per IANA [11] registry.
     * media-type -- MIME media-type of file contents per IANA registry.
     * charset    -- Character set per IANA registry (if not UTF-8)
     *
     * @param line The "facts" for a file in a reply to a MLSx command
     * @return Parsed keys and values
     */
    protected Map<String, Map<String, String>> parseFacts(final String line) {
        final Pattern p = Pattern.compile("\\s?(\\S+\\=\\S+;)*\\s(.*)");
        final Matcher result = p.matcher(line);
        Map<String, Map<String, String>> file = new HashMap<String, Map<String, String>>();
        if(result.matches()) {
            final String filename = result.group(2);
            final Map<String, String> facts = new HashMap<String, String>();
            for(String fact : result.group(1).split(";")) {
                String key = StringUtils.substringBefore(fact, "=");
                if(StringUtils.isBlank(key)) {
                    continue;
                }
                String value = StringUtils.substringAfter(fact, "=");
                if(StringUtils.isBlank(value)) {
                    continue;
                }
                facts.put(key.toLowerCase(java.util.Locale.ENGLISH), value);
            }
            file.put(filename, facts);
            return file;
        }
        log.warn("No match for " + line);
        return null;
    }

}
