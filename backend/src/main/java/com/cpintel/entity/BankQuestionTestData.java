package com.cpintel.entity;

import jakarta.persistence.*;
import lombok.*;

/** The bytes of one {@link BankQuestionTest}: its input and, once there is one, its answer. */
@Entity
@Table(name = "bank_question_test_data")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankQuestionTestData {

    @Id
    @Column(name = "test_id")
    private Long testId;

    @Column(name = "input", nullable = false)
    private byte[] input;

    @Column(name = "answer")
    private byte[] answer;
}
