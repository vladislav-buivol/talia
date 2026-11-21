"""Remove display-only markup and URLs before handing text to the synthesizer."""

from html import unescape
import re


TAGS = (
    "laughter", "sigh", "confirmation-en", "question-en", "question-ah", "question-oh",
    "question-ei", "question-yi", "surprise-ah", "surprise-oh", "surprise-wa", "surprise-yo",
    "dissatisfaction-hnn",
)
INLINE_LINK = re.compile(r"!?\[([^\[\]\n]+)\]\(\s*(?:<[^>\n]*>|(?:[^()\n]|\([^()\n]*\))*)\)")
URL = re.compile(
    r"\b(?:[a-z][a-z\d+.-]*://|(?:mailto|tel|data):|www\.)"
    r"(?:\[[\da-f:.%]+\][^\s<>\[\]{}\"'`]*|[^\s<>\[\]{}\"'`]+)"
    r"|\b[\w.+-]+@[\w.-]+\.[^\W\d_]{2,63}\b"
    r"|(?<![\w@])(?://)?(?:(?:[\w-]+\.)+(?:xn--[\w-]+|[^\W\d_]{2,63})(?![\w-])"
    r"|(?:localhost|(?:\d{1,3}\.){3}\d{1,3}|\[[\da-f:]+\])(?=[:/]))"
    r"(?::\d+)?(?:[/#?][^\s<>\[\]{}\"'`]*)?",
    re.IGNORECASE,
)
TRAILING_PUNCTUATION = re.compile(r"[.!?,;:…)]*$")


def speech_text(text: str, *, keep_tags: bool = False) -> str:
    text = unescape(text)
    text = re.sub(r"(?ms)^\s*(`{3,}|~{3,}).*?(?:^\s*\1[^\S\n]*$|\Z)", " ", text)
    text = re.sub(r"(?m)^\s*\[[^\]\n]+\]:[^\n]*", " ", text)
    text = INLINE_LINK.sub(r"\1", text)
    text = re.sub(r"!?\[([^\[\]\n]+)\]\[[^\]\n]*\]", r"\1", text)
    text = re.sub(r"<((?:https?://|mailto:)[^>]+)>", r"\1", text, flags=re.IGNORECASE)
    text = re.sub(r"</?[a-z][^<>]*>", " ", text, flags=re.IGNORECASE)
    text = URL.sub(lambda hit: " " + TRAILING_PUNCTUATION.search(hit[0])[0], text)

    def bracket(hit: re.Match) -> str:
        value = hit[1]
        if value in TAGS:
            return hit[0] if keep_tags else " "
        return " "

    text = re.sub(r"\[([^\[\]\n]+)\]", bracket, text)
    text = re.sub(r"(?m)^\s*(?:#{1,6}|>|[-+*]|\d+[.)])\s+", "", text)
    text = re.sub(r"[*_`~|]", "", text)
    text = re.sub(r"\s+([,.!?;:…])", r"\1", " ".join(text.split())).strip()
    words = re.sub(r"\[[^\]]+\]", "", text)
    return text if any(char.isalnum() for char in words) else ""


def split_speech(text: str) -> list[str]:
    chunks = []
    for sentence in re.split(r"(?<=[.!?…])\s+(?=[\w\[])", text):
        while len(sentence) > 480:
            end = sentence.rfind(" ", 0, 480)
            if end < 0:
                end = 480
            chunks.append(sentence[:end])
            sentence = sentence[end:].lstrip()
        if sentence:
            chunks.append(sentence)
    return chunks
