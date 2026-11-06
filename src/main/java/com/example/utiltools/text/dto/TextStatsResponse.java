package com.example.utiltools.text.dto;

/**
 * Statistics for the analysed text.
 *
 * @param characters           Unicode code points including whitespace
 * @param charactersNoWhitespace Unicode code points excluding whitespace
 * @param words                runs of letters and digits
 * @param lines                newline-separated segments
 * @param sentences            non-blank segments split on {@code .}, {@code !} or {@code ?}
 */
public record TextStatsResponse(
        int characters,
        int charactersNoWhitespace,
        int words,
        int lines,
        int sentences
) {}
