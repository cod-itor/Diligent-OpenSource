"""{{label}}: a command-line program with argparse. Try: python main.py --help"""

import argparse
import sys


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Says hello, a few times if you ask.")
    parser.add_argument("--name", default="{{label}}", help="who to greet")
    parser.add_argument("--times", type=int, default=1, help="how many times")
    parser.add_argument("--shout", action="store_true", help="in capitals")
    args = parser.parse_args(argv)

    line = f"Hello from {args.name} on Python {sys.version.split()[0]}"
    for _ in range(max(1, args.times)):
        print(line.upper() if args.shout else line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
