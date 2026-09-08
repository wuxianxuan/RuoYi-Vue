-- 回填历史最高连板数（一次性）
-- 解析 stock_limit_up_record.up_num（如「8天8板」）中「板」前面的数字作为连板数，
-- 按股票取历史最大值写入 stock.max_limit_up_board。
-- 仅当大于当前值才覆盖（可重复执行，幂等）。
UPDATE stock s
JOIN (
    SELECT secu_code,
           MAX(CAST(REGEXP_SUBSTR(up_num, '[0-9]+(?=板)') AS UNSIGNED)) AS max_board
    FROM stock_limit_up_record
    WHERE up_num REGEXP '[0-9]+板'
    GROUP BY secu_code
) t ON s.stock_code = t.secu_code
SET s.max_limit_up_board = t.max_board
WHERE (s.max_limit_up_board IS NULL OR s.max_limit_up_board < t.max_board);
