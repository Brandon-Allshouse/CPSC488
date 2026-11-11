package edu.cpsc488.brainfeed.study;

import java.util.List;

/**
 * A video's summary and quiz questions, written by the LLM and checked by {@link StudyParser}.
 * Every string here started as LLM output, so the frontend shows it as plain text only.
 */
public record StudyMaterial(String summary, List<Question> questions) {

    /** One multiple-choice question. answerIndex points into options (0-3). */
    public record Question(String question, List<String> options, int answerIndex, String explanation) {
    }
}
