"""Every session join code carries a digit, so the top-bar scanner never takes a word for one."""

from app.questionnaire.common import JOIN_ALPHABET, JOIN_CODE_LENGTH, new_join_code


def test_every_join_code_has_a_digit_and_keeps_the_alphabet():
    for _ in range(2000):
        code = new_join_code()
        assert len(code) == JOIN_CODE_LENGTH
        assert set(code) <= set(JOIN_ALPHABET)
        assert any(character.isdigit() for character in code), code
