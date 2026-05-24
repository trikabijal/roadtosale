CREATE TABLE ai_assessments (
    id BIGSERIAL PRIMARY KEY,
    user_checksheet_id BIGINT NOT NULL,
    chks_question_result_id BIGINT NOT NULL,
    photo_path VARCHAR(500) NOT NULL,
    suggested_judgement VARCHAR(10) NOT NULL,
    explanation TEXT,
    confidence DOUBLE PRECISION,
    ai_model VARCHAR(100),
    prompt_sent TEXT,
    raw_response TEXT,
    assessed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT,
    deleted_by BIGINT,
    CONSTRAINT fk_ai_assessments_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id),
    CONSTRAINT fk_ai_assessments_chks_question_result_id FOREIGN KEY (chks_question_result_id) REFERENCES chks_question_results(id),
    CONSTRAINT fk_ai_assessments_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_ai_assessments_updated_by FOREIGN KEY (updated_by) REFERENCES users(id),
    CONSTRAINT fk_ai_assessments_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id)
);

CREATE INDEX idx_ai_assessments_user_checksheet_id ON ai_assessments(user_checksheet_id);
CREATE INDEX idx_ai_assessments_chks_question_result_id ON ai_assessments(chks_question_result_id);
CREATE INDEX idx_ai_assessments_lookup ON ai_assessments(user_checksheet_id, chks_question_result_id);
