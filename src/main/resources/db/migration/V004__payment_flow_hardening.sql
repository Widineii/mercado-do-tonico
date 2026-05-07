CREATE INDEX IF NOT EXISTS idx_venda_pagamentos_forma ON venda_pagamentos(forma, venda_id);
CREATE INDEX IF NOT EXISTS idx_caixa_operacoes_tipo_data ON caixa_operacoes(tipo, timestamp, caixa_id);
CREATE INDEX IF NOT EXISTS idx_fiado_pagamentos_data ON fiado_pagamentos(data, fiado_id);
