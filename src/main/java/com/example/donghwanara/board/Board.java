package com.example.donghwanara.board;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "boards")
public class Board {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 50)
    private String title;

    @Column(length = 256)
    private String contents;

    @Column(nullable = false, length = 256)
    private String summary;

    @Column(nullable = false)
    private Integer status;

    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;

    @Column(name = "deleted_date")
    private LocalDateTime deletedDate;

    public Board(String title, String contents, String summary, Integer status) {
        this.title = title;
        this.contents = contents;
        this.summary = summary;
        this.status = status;
    }

    @PrePersist
    void prePersist() {
        if (createdDate == null) {
            createdDate = LocalDateTime.now();
        }
    }

    public void update(String title, String contents, String summary, Integer status) {
        this.title = title;
        this.contents = contents;
        this.summary = summary;
        this.status = status;
    }

    public void delete() {
        this.deletedDate = LocalDateTime.now();
    }
}
