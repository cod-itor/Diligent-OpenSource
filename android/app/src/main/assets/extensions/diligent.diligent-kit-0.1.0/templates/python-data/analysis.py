"""{{label}}: a first look at some data with numpy and pandas."""

import numpy as np
import pandas as pd


def main() -> None:
    rng = np.random.default_rng(seed=1)
    frame = pd.DataFrame({
        "day": pd.date_range("2026-01-01", periods=7),
        "steps": rng.integers(2000, 12000, size=7),
    })
    print(frame)
    print(f"{len(frame)} rows, average {frame['steps'].mean():.0f} steps a day")


if __name__ == "__main__":
    main()
