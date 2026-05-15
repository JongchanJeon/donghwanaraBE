package com.example.donghwanara.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardRepository extends JpaRepository<Board, Integer> {

    List<Board> findByDeletedDateIsNullOrderByIdDesc();

    Optional<Board> findByIdAndDeletedDateIsNull(Integer id);

    Optional<Board> findByTitleAndDeletedDateIsNull(String title);
}
