#!/usr/bin/env python3
"""Validate SDK release identity and the actual Maven consumer artifacts."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TAG = re.compile(r"agentic-sdk-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")


def release_version(tag):
    if not TAG.fullmatch(tag):
        raise ValueError("release tag must be agentic-sdk-vMAJOR.MINOR.PATCH, without SNAPSHOT or leading zeros")
    return tag.removeprefix("agentic-sdk-v")


def read_project(project):
    pom = ET.parse(project / "pom.xml").getroot()
    if pom.find("m:parent", NS) is not None:
        raise ValueError("SDK POM must build without a parent project")
    if pom.findtext("m:groupId", namespaces=NS) != "com.aliyun.odps" or pom.findtext("m:artifactId", namespaces=NS) != "agentic-sdk":
        raise ValueError("unexpected SDK Maven coordinates")
    version = pom.findtext("m:version", namespaces=NS)
    if version == "${revision}":
        version = pom.findtext("m:properties/m:revision", namespaces=NS)
    if not version or "${" in version:
        raise ValueError("SDK version is missing or unresolved")
    examples = ET.parse(project / "examples/pom.xml").getroot()
    example_version = examples.findtext("m:properties/m:agentic-sdk.version", namespaces=NS)
    if example_version != version:
        raise ValueError("example dependency must match the SDK version being tested")
    return version


def check_artifacts(project, version, signed=False):
    target = project / "target"
    base = "agentic-sdk-" + version
    files = [target / (base + suffix) for suffix in (".jar", "-sources.jar", "-javadoc.jar")]
    for path in files:
        if not path.is_file():
            raise ValueError("missing artifact: " + path.name)
    with ZipFile(files[0]) as jar:
        for entry in ("com/aliyun/odps/agentic/HarnessEngine.class", "META-INF/LICENSE", "META-INF/NOTICE", "prompts/default.txt"):
            if entry not in jar.namelist():
                raise ValueError("missing JAR entry: " + entry)
        prompt_entries = [entry for entry in jar.namelist() if entry.startswith("prompts/") and entry.endswith(".txt")]
        expected_prompts = {"prompts/default.txt", "prompts/agent/compaction.txt", "prompts/agent/explore.txt", "prompts/agent/summary.txt", "prompts/agent/title.txt"}
        if set(prompt_entries) != expected_prompts:
            raise ValueError("JAR contains missing or obsolete prompt resources")
    with ZipFile(files[1]) as jar:
        if "com/aliyun/odps/agentic/HarnessEngine.java" not in jar.namelist():
            raise ValueError("sources JAR lacks kernel source")
    with ZipFile(files[2]) as jar:
        if "index.html" not in jar.namelist():
            raise ValueError("Javadoc JAR lacks documentation")
    consumer_path = project / ".flattened-pom.xml"
    consumer = ET.parse(consumer_path).getroot()
    if consumer.find("m:parent", NS) is not None or consumer.findtext("m:version", namespaces=NS) != version:
        raise ValueError("consumer POM has a parent or unresolved/mismatched version")
    for field in ("name", "description", "url", "licenses/license/name", "developers/developer/name", "scm/url"):
        if not consumer.findtext("m:" + field.replace("/", "/m:"), namespaces=NS):
            raise ValueError("missing Central POM metadata: " + field)
    if signed:
        for path in files + [consumer_path]:
            signature = path.with_name(path.name + ".asc")
            # GPG signs the deployed POM under its artifact filename in target.
            if path == consumer_path:
                signature = target / (base + ".pom.asc")
            if not signature.is_file():
                raise ValueError("missing signature: " + signature.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-dir", type=Path, default=Path("."))
    parser.add_argument("--tag")
    parser.add_argument("--artifacts", action="store_true")
    parser.add_argument("--signed", action="store_true")
    parser.add_argument("--print-version", action="store_true")
    args = parser.parse_args()
    try:
        version = read_project(args.project_dir)
        if args.tag and release_version(args.tag) != version:
            raise ValueError("tag version must match the SDK version in pom.xml; update the version before tagging")
        if args.artifacts:
            check_artifacts(args.project_dir, version, args.signed)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, "Release validation failed: " + str(error) + "\n")
    print(version if args.print_version else "SDK release validation passed: " + version)


if __name__ == "__main__":
    main()
