-- 回填历史最高连板数及对应交易日（一次性）
-- 解析 stock_limit_up_record.up_num（如「8天8板」）中「板」前面的数字作为连板数，
-- 按股票取历史最大连板数及其最早达成的交易日，写入 stock.max_limit_up_board / max_limit_up_board_date。
-- 幂等：仅当连板数或日期为空/连板数更大时才更新，可重复执行。
UPDATE stock s
JOIN (
    SELECT secu_code, board AS max_board, trade_date AS board_date
    FROM (
        SELECT secu_code,
               trade_date,
               CAST(REGEXP_SUBSTR(up_num, '[0-9]+(?=板)') AS UNSIGNED) AS board,
               ROW_NUMBER() OVER (
                   PARTITION BY secu_code
                   ORDER BY CAST(REGEXP_SUBSTR(up_num, '[0-9]+(?=板)') AS UNSIGNED) DESC, trade_date ASC
               ) AS rn
        FROM stock_limit_up_record
        WHERE up_num REGEXP '[0-9]+板'
    ) t
    WHERE rn = 1
) m ON s.stock_code = m.secu_code
SET s.max_limit_up_board = m.max_board,
    s.max_limit_up_board_date = m.board_date
WHERE s.max_limit_up_board_date IS NULL
   OR s.max_limit_up_board IS NULL
   OR s.max_limit_up_board < m.max_board;
