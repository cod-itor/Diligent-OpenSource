"""{{label}}: read a CSV file and sum it up with Python's own csv and statistics modules."""

import csv
import statistics
from collections import defaultdict


def main() -> None:
    by_day = defaultdict(list)
    with open("steps.csv", newline="") as f:
        for row in csv.DictReader(f):
            by_day[row["weekday"]].append(int(row["steps"]))

    everything = [n for values in by_day.values() for n in values]
    for day, values in by_day.items():
        print(f"{day:<10} {statistics.mean(values):>8.0f} steps on average")
    print(f"{len(everything)} rows, average {statistics.mean(everything):.0f}, "
          f"median {statistics.median(everything):.0f}")


if __name__ == "__main__":
    main()
