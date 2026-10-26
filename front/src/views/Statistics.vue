<script setup>
  import * as echarts from 'echarts'
  import {onMounted, reactive} from "vue";
  import request from "@/utils/request";

  const state = reactive({
        form:{
    }
  })

  onMounted(() => {
    //获取数量

    load()

  })

  const load = () =>{

    let leaveStaticsOption = {
      title: {
        text: '旧请假类型分布（申请条数）',
        left: 'center'
      },
      tooltip: {
        trigger: 'item',
        formatter: '{b}: {c} ({d}%)' // 添加此行以显示百分比
      },
      legend: {
        orient: 'vertical',
        left: 'left'
      },
      series: [
        {
          type: 'pie',
          radius: '50%',
          data: [],
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.5)'
            }
          }
        }
      ]
    };
    let chart_leaveStatics = echarts.init(document.getElementById("chart_leaveStatics"))
    request.get('/statistics/leaveStatics',{
      params:{
      }
    }).then(res => {
      if(res.data==null){
        chart_leaveStatics.setOption(leaveStaticsOption)
        return;
      }
      leaveStaticsOption.series[0].data = res.data
      // 绘制图表
      chart_leaveStatics.setOption(leaveStaticsOption)
    })







    let recordsStaticsOption = {
      title: {
        text: '原始事件状态分布（事件条数）',
        left: 'center'
      },
      tooltip: {
        trigger: 'item'
      },
      legend: {
        orient: 'vertical',
        left: 'left'
      },
      xAxis: {
        type: 'category',
        data: []
      },
      yAxis: {
        type: 'value'
      },
      series: [
        {
          data: [],
          type: 'bar',
          itemStyle: {
            color: function() {
              var letters = "0123456789ABCDEF";
              var color = "#";
              for (var i = 0; i < 6; i++) {
                color += letters[Math.floor(Math.random() * 16)];
              }
              return color;
            }
          }
        }
      ]
    };
    let chart_recordsStatics = echarts.init(document.getElementById("chart_recordsStatics"))
    request.get('/statistics/recordsStatics',{
      params:{
      }
    }).then(res => {
      if(res.data==null){
        chart_recordsStatics.setOption(recordsStaticsOption)
        return;
      }
      recordsStaticsOption.series[0].data = res.data.map(v => v.value)
      recordsStaticsOption.xAxis.data = res.data.map(v => v.name)
      // 绘制图表
      chart_recordsStatics.setOption(recordsStaticsOption)
    })




  }




</script>

<template>
  <div style="background-color: #ffffff;padding: 10px;border-radius: 10px;margin-top: 20px;">
    <p style="font-size:16px;line-height:1.6;color:#46515d">此页统计可见原始事件与旧请假申请的条数，不能作为出勤天数或新日结结果。请前往 <router-link to="/attendance">考勤工作台</router-link> 核对每日结果、批准请假与补卡。</p>
    <div>
      <el-row :gutter="10">

      </el-row>
    </div>

    <div style="margin: 20px 0;">
      <el-row :gutter="10">

            <el-col :span="24">
              <div style="width:100%; height: 500px" id="chart_leaveStatics"></div>
            </el-col>
            <el-col :span="24">
              <div style="width:100%; height: 500px" id="chart_recordsStatics"></div>
            </el-col>
      </el-row>
    </div>


  </div>
</template>
