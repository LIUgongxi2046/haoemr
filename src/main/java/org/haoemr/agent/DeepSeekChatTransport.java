package org.haoemr.agent;

import java.util.Map;

interface DeepSeekChatTransport {

    Map<String, Object> complete(Map<String, Object> request);
}
