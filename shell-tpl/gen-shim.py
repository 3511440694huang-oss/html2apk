#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate Shim.java from shim.js (dev-time only).

The shim is embedded in classes.dex so the product shell has no runtime
dependency on a separate asset file.  Java string literals are limited to
64KB of modified-UTF8, so the JS is split into ~12KB chunks.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src", "com", "html2apk", "shell", "shim.js")
MAX_CHUNK = 12000


def java_escape(s):
    out = []
    for ch in s:
        o = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif o < 0x20 or o == 0x7F:
            # Java \uXXXX is processed before tokenizing, so use octal escapes
            out.append("\\%03o" % o)
        else:
            out.append(ch)
    return "".join(out)


def split_chunks(text):
    chunks, cur = [], []
    size = 0
    for line in text.splitlines(True):
        b = len(line.encode("utf-8"))
        if size + b > MAX_CHUNK and cur:
            chunks.append("".join(cur))
            cur, size = [], 0
        # A single very long line still has to fit one literal.
        while b > MAX_CHUNK:
            head, line = line[: MAX_CHUNK // 2], line[MAX_CHUNK // 2:]
            if cur:
                chunks.append("".join(cur))
                cur, size = [], 0
            chunks.append(head)
            b = len(line.encode("utf-8"))
        cur.append(line)
        size += b
    if cur:
        chunks.append("".join(cur))
    return chunks


def main():
    with open(SRC, "r", encoding="utf-8") as f:
        text = f.read()
    chunks = split_chunks(text)
    print("package com.html2apk.shell;")
    print()
    print("/** Generated from shim.js by gen-shim.py - do not edit by hand. */")
    print("final class Shim {")
    print("    private static final String[] CHUNKS = new String[]{")
    for c in chunks:
        print('        "%s",' % java_escape(c))
    print("    };")
    print("    private static String cached;")
    print()
    print("    private Shim() {")
    print("    }")
    print()
    print("    static String get() {")
    print("        String s = cached;")
    print("        if (s == null) {")
    print("            StringBuilder sb = new StringBuilder(%d);" % (len(text.encode("utf-8")) + 64))
    print("            for (int i = 0; i < CHUNKS.length; i++) {")
    print("                sb.append(CHUNKS[i]);")
    print("            }")
    print("            s = sb.toString();")
    print("            cached = s;")
    print("        }")
    print("        return s;")
    print("    }")
    print("}")


if __name__ == "__main__":
    main()
