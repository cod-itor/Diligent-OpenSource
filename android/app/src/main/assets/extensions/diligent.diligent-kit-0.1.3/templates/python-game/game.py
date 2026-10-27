"""{{label}}: guess the number, a game for the terminal.

Run it with the Run button and type your guesses. `python game.py --demo`
plays one round by itself.
"""

import random
import sys


def play(guesses, secret: int, out=print) -> int:
    out("Hello from {{label}}. I am thinking of a number from 1 to 100.")
    tries = 0
    for text in guesses:
        try:
            guess = int(text)
        except ValueError:
            out("Type a whole number.")
            continue
        tries += 1
        if guess < secret:
            out("Higher.")
        elif guess > secret:
            out("Lower.")
        else:
            out(f"Got it in {tries} tries!")
            return tries
    out(f"Out of guesses. It was {secret}.")
    return tries


def typed():
    while True:
        try:
            yield input("Your guess: ")
        except EOFError:
            return


def demo(secret: int):
    low, high = 1, 100
    while low <= high:
        guess = (low + high) // 2
        print(f"Your guess: {guess}")
        yield str(guess)
        if guess < secret:
            low = guess + 1
        else:
            high = guess - 1


if __name__ == "__main__":
    number = random.randint(1, 100)
    play(demo(number) if "--demo" in sys.argv else typed(), number)
