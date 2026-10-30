#!/usr/bin/env python3
"""Validate the complete legal package, then optionally import immutable-labelled CMS drafts.

The token is read from LEGAL_ADMIN_TOKEN. This tool never publishes or overwrites revisions.
"""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

LOCALES = ("en-US", "zh-CN", "zh-TW", "es-ES", "fr-FR", "de-DE", "pt-BR", "ru-RU", "ar-SA", "ja-JP", "ko-KR", "hi-IN")
TYPES = ("USER_AGREEMENT", "PRIVACY_POLICY", "COOKIE_POLICY", "TRADING_RISK", "DERIVATIVES_RISK", "FUNDS_AND_WITHDRAWALS", "FEES", "AML_KYC", "COMPLAINTS", "SECURITY_NOTICE")


def package(source):
    files = {locale: json.loads((source / f"{locale}.json").read_text(encoding="utf-8")) for locale in LOCALES}
    for locale, documents in files.items():
        if set(documents) != set(TYPES):
            raise ValueError(f"{locale}: document types do not match")
        for kind, document in documents.items():
            if not isinstance(document["title"], str) or not 1 <= len(document["title"].strip()) <= 160:
                raise ValueError(f"{locale}/{kind}: invalid title")
            if len(document["sections"]) != len(files["en-US"][kind]["sections"]):
                raise ValueError(f"{locale}/{kind}: incomplete sections")
            if any(not isinstance(value, str) or not value.strip() for section in document["sections"] for value in section):
                raise ValueError(f"{locale}/{kind}: empty section")
            if any(len(section) != 2 for section in document["sections"]):
                raise ValueError(f"{locale}/{kind}: invalid section structure")
            if locale not in ("en-US", "zh-CN", "zh-TW") and document == files["en-US"][kind]:
                raise ValueError(f"{locale}/{kind}: English placeholder is not a translation")
    result = []
    for kind in TYPES:
        translations = []
        for locale in LOCALES:
            document = files[locale][kind]
            body = "".join(f"<h2>{html.escape(heading)}</h2><p>{html.escape(text)}</p>" for heading, text in document["sections"])
            if len(body) > 64000:
                raise ValueError(f"{locale}/{kind}: article exceeds the CMS limit")
            translations.append({"locale": locale, "title": document["title"], "bodyHtml": body})
        digest = hashlib.sha256(json.dumps(translations, ensure_ascii=False, sort_keys=True).encode()).hexdigest()[:12]
        result.append({"type": kind, "revisionLabel": f"2026-10-31.{digest}", "translations": translations})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parent / "source")
    parser.add_argument("--url", default="http://127.0.0.1:9094")
    parser.add_argument("--apply", action="store_true", help="Import drafts using LEGAL_ADMIN_TOKEN; never publish")
    args = parser.parse_args()
    documents = package(args.source)
    print(f"Validated {len(TYPES)} document types, {len(LOCALES)} languages and all section counts.")
    if not args.apply:
        return
    base = urllib.parse.urlsplit(args.url)
    if base.username or base.password or base.query or base.fragment or base.path not in ("", "/"):
        raise ValueError("Supply an origin without embedded credentials, a path or a query")
    if base.scheme != "https" and not (base.scheme == "http" and base.hostname in ("127.0.0.1", "localhost", "::1")):
        raise ValueError("Remote CMS access requires HTTPS")
    token = os.environ.get("LEGAL_ADMIN_TOKEN", "")
    if not token or "\n" in token or "\r" in token:
        raise ValueError("Set LEGAL_ADMIN_TOKEN without putting it in command-line arguments")
    origin = args.url.rstrip("/")

    def request(path, body=None):
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        if payload is not None and len(payload) > 3 * 1024 * 1024:
            raise ValueError("Draft exceeds the UTF-8 request limit")
        req = urllib.request.Request(origin + path, data=payload, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                result = response.read(8 * 1024 * 1024 + 1)
                if len(result) > 8 * 1024 * 1024:
                    raise ValueError("CMS response exceeds the size limit")
                return json.loads(result)
        except urllib.error.HTTPError as failure:
            # Do not echo a response or request that might contain credentials or unpublished text.
            raise RuntimeError(f"CMS request failed with HTTP {failure.code}") from None

    existing = {}
    for offset in range(0, 10000, 50):
        page = request(f"/api/v1/admin/legal/revisions?offset={offset}&limit=50")
        existing.update({(row["type"], row["revisionLabel"]): row for row in page["revisions"]})
        if not page["hasMore"]:
            break
    else:
        raise ValueError("Too many revisions; narrow the import manually")
    for document in documents:
        previous = existing.get((document["type"], document["revisionLabel"]))
        if previous:
            print(f"Existing {document['type']} revision {previous['revisionId']} ({previous['status']}); left unchanged.")
            continue
        saved = request("/api/v1/admin/legal/revisions", document)
        if saved["type"] != document["type"] or saved["revisionLabel"] != document["revisionLabel"] or saved["status"] != "DRAFT":
            raise ValueError("Unexpected CMS response; verify the last import before continuing")
        print(f"Created {document['type']} draft {saved['revisionId']} ({document['revisionLabel']}).")


if __name__ == "__main__":
    main()
