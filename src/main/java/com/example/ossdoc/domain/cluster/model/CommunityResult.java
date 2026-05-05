package com.example.ossdoc.domain.cluster.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CommunityResult {
    /*
    * node index -> cluster id
    * 예 : cluster[3] = 1이면?
    * 3번 node는 1번 community에 속한다는 의미
    * */
    private int[] clusters;
}
