# node_exporter_collector
Jmeter中，setup线程组收集node_exporter数据，解析并存入mysql数据库，teardown调用存储过程计算最终结果

存储过程：
ALTER USER 'root'@'%' IDENTIFIED BY 'Ttt01007';

DELIMITER //

CREATE PROCEDURE UpdateServerSummary(IN task_name_param VARCHAR(255))
BEGIN
-- 删除总信息表中指定任务名称的记录
DELETE FROM server_summary_info WHERE task_name = task_name_param;

    -- 插入统计数据到总信息表中
    INSERT INTO server_summary_info (task_name, ip, cpu, memory, io, network, reserved1, reserved2, reserved3, reserved4, reserved5)
    SELECT 
        task_name, 
        ip, 
        ROUND(AVG(cpu), 2) AS cpu, 
        ROUND(AVG(memory), 2) AS memory, 
        ROUND(AVG(io), 2) AS io, 
        ROUND(AVG(network), 2) AS network, 
        reserved1, 
        reserved2, 
        reserved3, 
        reserved4, 
        reserved5
    FROM 
        server_detail_info
    WHERE
        task_name = task_name_param
    GROUP BY 
        task_name, ip, reserved1, reserved2, reserved3, reserved4, reserved5;
END//

DELIMITER ;

详细数据表：
CREATE TABLE server_detail_info (
detail_id INT AUTO_INCREMENT PRIMARY KEY,         -- 详细信息ID，自增
task_name VARCHAR(255) NOT NULL,                   -- 任务名称
ip VARCHAR(15) NOT NULL,                           -- 服务器IP地址
cpu DECIMAL(5, 2) NOT NULL,                        -- CPU使用率
memory DECIMAL(5, 2) NOT NULL,                     -- 内存使用率
io DECIMAL(5, 2) NOT NULL,                         -- I/O使用率
network DECIMAL(5, 2) NOT NULL,                    -- 网络使用率
reserved1 VARCHAR(255),                            -- 保留字段1
reserved2 VARCHAR(255),                            -- 保留字段2
reserved3 VARCHAR(255),                            -- 保留字段3
reserved4 VARCHAR(255),                            -- 保留字段4
reserved5 VARCHAR(255),                            -- 保留字段5
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    -- 创建时间
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP -- 更新时间
);

汇总表：
CREATE TABLE server_summary_info (
server_id INT AUTO_INCREMENT PRIMARY KEY,          
task_name VARCHAR(255) NOT NULL,                   
ip VARCHAR(15) NOT NULL,                           
cpu DECIMAL(5, 2) NOT NULL,                        
memory DECIMAL(5, 2) NOT NULL,                     
io DECIMAL(5, 2) NOT NULL,                         
network DECIMAL(5, 2) NOT NULL,                    
reserved1 VARCHAR(50),                            -- 缩短字段长度
reserved2 VARCHAR(50),                            
reserved3 VARCHAR(50),                            
reserved4 VARCHAR(50),                            
reserved5 VARCHAR(50),                            
created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,    
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,  
UNIQUE (task_name, ip, reserved1, reserved2, reserved3, reserved4, reserved5)
);


