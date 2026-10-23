#!/usr/bin/env python3
"""Read and optionally verify the version from the checked-out XcodeGen spec."""

import pathlib
import re
import sys


def main() -> None:
    if len(sys.argv) not in (2, 3):
        raise SystemExit("usage: ios-version.py ios/project.yml [expected-version]")
    spec = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")

    def setting(name: str) -> str:
        match = re.search(rf'^\s*{name}:\s*["\']?([^"\'\s#]+)', spec, re.MULTILINE)
        if not match:
            raise SystemExit(f"Missing {name} in {sys.argv[1]}")
        return match.group(1)

    version = setting("MARKETING_VERSION")
    build = setting("CURRENT_PROJECT_VERSION")
    expected = sys.argv[2] if len(sys.argv) == 3 else ""
    if expected and expected != version:
        raise SystemExit(f"Version mismatch: source={version} expected={expected}")
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+_-]*", version):
        raise SystemExit("Unsafe iOS Version")
    if not re.fullmatch(r"[0-9]+", build):
        raise SystemExit("iOS Build Number must be numeric")
    print(f"version={version}")
    print(f"build={build}")


if __name__ == "__main__":
    main()
