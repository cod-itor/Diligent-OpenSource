"""{{label}}: a small Python script. Run it with the Run button or `kit`'s hint."""

import sys


def main() -> None:
    print(f"Hello from {{label}} on Python {sys.version.split()[0]}")


if __name__ == "__main__":
    main()
